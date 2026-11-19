package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 客户端盲签名令牌测试（Phase 2c）。
 *
 * <p>用本地 RSA 密钥模拟服务端：致盲 → 签名 → 去盲 → 自检必须成立，
 * 换 nonce 或换密钥必须失败。跨模块（服务端 FDH）由 e2e 覆盖。
 *
 * [INPUT]: 只依赖 JDK RSA 与 BlindRsaToken
 * [OUTPUT]: 验证往返、令牌格式、篡改拒绝
 * [POS]: /message/anonymous 凭证生成的单元测试
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class BlindRsaTokenTest {

    private static String publicKeyBase64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String signBlinded(String blindedBase64, KeyPair pair) {
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        BigInteger blinded = new BigInteger(1, Base64.getUrlDecoder().decode(blindedBase64));
        BigInteger signature = blinded.modPow(privateKey.getPrivateExponent(),
            privateKey.getModulus());
        int length = (privateKey.getModulus().bitLength() + 7) / 8;
        byte[] raw = signature.toByteArray();
        byte[] fixed = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, fixed, length - copy, copy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    @Test
    @DisplayName("致盲签名往返后令牌可通过自检")
    void roundTrip() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String publicKey = publicKeyBase64(pair);

        byte[] nonce = BlindRsaToken.newNonce();
        BlindRsaToken.Blinded blinded = BlindRsaToken.blind(nonce, publicKey);
        String token = BlindRsaToken.unblind(blinded, signBlinded(blinded.getBlindedValue(), pair));

        assertTrue(BlindRsaToken.verify(token, publicKey));
        assertTrue(token.contains("."), "令牌格式必须是 nonce.signature");
    }

    @Test
    @DisplayName("换 nonce 或乱改签名都会自检失败")
    void tamperedTokenRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String publicKey = publicKeyBase64(pair);

        BlindRsaToken.Blinded blinded = BlindRsaToken.blind(BlindRsaToken.newNonce(), publicKey);
        String token = BlindRsaToken.unblind(blinded, signBlinded(blinded.getBlindedValue(), pair));
        assertTrue(BlindRsaToken.verify(token, publicKey));

        String[] parts = token.split("\\.");
        byte[] nonce = Base64.getUrlDecoder().decode(parts[0]);
        nonce[8] ^= 0x01;
        String replaced = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            + "." + parts[1];
        assertFalse(BlindRsaToken.verify(replaced, publicKey), "换 nonce 必须失败");

        KeyPairGenerator other = KeyPairGenerator.getInstance("RSA");
        other.initialize(2048);
        assertFalse(BlindRsaToken.verify(token, publicKeyBase64(other.generateKeyPair())),
            "换公钥必须失败");
    }

    @Test
    @DisplayName("每次致盲的请求值都不同（blinding factor 随机）")
    void blindingIsRandomized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String publicKey = publicKeyBase64(generator.generateKeyPair());
        byte[] nonce = BlindRsaToken.newNonce();
        assertNotEquals(BlindRsaToken.blind(nonce, publicKey).getBlindedValue(),
            BlindRsaToken.blind(nonce, publicKey).getBlindedValue());
    }

    @Test
    @DisplayName("nonce 带时间戳，长度固定")
    void nonceShape() {
        byte[] nonce = BlindRsaToken.newNonce();
        assertEquals(32, nonce.length);
        long issuedAt = 0L;
        for (int i = 0; i < 8; i++) {
            issuedAt = (issuedAt << 8) | (nonce[i] & 0xffL);
        }
        assertTrue(Math.abs(System.currentTimeMillis() - issuedAt) < 5000);
    }
}
