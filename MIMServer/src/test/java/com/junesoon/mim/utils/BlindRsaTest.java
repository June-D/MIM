package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 盲签名 FDH 与 RSA 运算测试（Phase 2c）。
 *
 * <p>验证致盲→签名→去盲→验签的完整往返，以及越界值与篡改必须失败。
 *
 * [INPUT]: 只依赖 JDK RSA
 * [OUTPUT]: 验证 FDH 确定性、盲签名往返与拒绝条件
 * [POS]: 服务端令牌签验的密码学原语
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class BlindRsaTest {

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    @Test
    @DisplayName("FDH 对同一 nonce 与模数必须稳定")
    void fdhDeterministic() throws Exception {
        RSAPublicKey key = (RSAPublicKey) keyPair().getPublic();
        byte[] nonce = nonce();
        BigInteger first = BlindRsa.fdh(nonce, key.getModulus());
        BigInteger second = BlindRsa.fdh(nonce, key.getModulus());
        assertEquals(first, second);
        assertTrue(first.signum() > 0 && first.compareTo(key.getModulus()) < 0);
    }

    @Test
    @DisplayName("致盲签名往返后可用公钥验证")
    void blindSignatureRoundTrip() throws Exception {
        KeyPair pair = keyPair();
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        byte[] nonce = nonce();

        BigInteger message = BlindRsa.fdh(nonce, publicKey.getModulus());
        BigInteger factor = new BigInteger(2047, new SecureRandom())
            .mod(publicKey.getModulus());
        BigInteger blinded = message.multiply(
            factor.modPow(publicKey.getPublicExponent(), publicKey.getModulus()))
            .mod(publicKey.getModulus());

        BigInteger signature = BlindRsa.signBlinded(blinded, privateKey);
        BigInteger unblinded = signature.multiply(factor.modInverse(publicKey.getModulus()))
            .mod(publicKey.getModulus());

        assertTrue(BlindRsa.verify(unblinded, nonce, publicKey));
        byte[] tampered = nonce.clone();
        tampered[0] ^= 0x01;
        assertFalse(BlindRsa.verify(unblinded, tampered, publicKey),
            "换 nonce 必须验签失败");
    }

    @Test
    @DisplayName("越界的致盲值被拒绝")
    void outOfRangeRejected() throws Exception {
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair().getPrivate();
        assertThrows(IllegalArgumentException.class,
            () -> BlindRsa.signBlinded(privateKey.getModulus(), privateKey));
    }
}
