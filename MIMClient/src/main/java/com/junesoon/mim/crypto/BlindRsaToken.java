package com.junesoon.mim.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 盲签名令牌的客户端实现（Phase 2c）。
 *
 * <p>服务端只对「致盲后」的值签名，因此无法把令牌与账号对应；
 * 客户端收到签名后去盲，得到可验证的一次性令牌。
 *
 * <p>FDH 必须与服务端 {@code BlindRsa} 逐字节一致：
 * 计数器模式扩展 SHA-256，再把候选值按拒绝采样压进 [0, n)。
 *
 * [INPUT]: 只依赖 JDK 的 BigInteger / MessageDigest / RSA
 * [OUTPUT]: newNonce / blind / unblind / verify
 * [POS]: SenderTokenService 与 /message/anonymous 的凭证生成
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class BlindRsaToken {

    private static final String INFO = "MIM/SENDER-TOKEN/1";

    /** nonce 结构：8 字节时间戳 + 24 字节随机。 */
    private static final int NONCE_BYTES = 32;

    private BlindRsaToken() {
    }

    /**
     * 生成 nonce：前 8 字节是当前时间戳（大端），服务端据此判断令牌是否过期。
     */
    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        long now = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) (now >>> (56 - 8 * i));
        }
        byte[] random = new byte[NONCE_BYTES - 8];
        new SecureRandom().nextBytes(random);
        System.arraycopy(random, 0, nonce, 8, random.length);
        return nonce;
    }

    /**
     * 致盲。返回的 {@link Blinded} 既带请求值，也带去盲所需的因子。
     */
    public static Blinded blind(byte[] nonce, String publicKeyBase64) throws Exception {
        RSAPublicKey key = parse(publicKeyBase64);
        BigInteger modulus = key.getModulus();
        BigInteger exponent = key.getPublicExponent();
        BigInteger message = fdh(nonce, modulus);

        SecureRandom random = new SecureRandom();
        BigInteger factor;
        do {
            factor = new BigInteger(modulus.bitLength(), random).mod(modulus);
        } while (factor.signum() == 0 || factor.compareTo(BigInteger.ONE) == 0
            || !factor.gcd(modulus).equals(BigInteger.ONE));

        BigInteger blinded = message.multiply(factor.modPow(exponent, modulus)).mod(modulus);
        Blinded result = new Blinded();
        result.nonce = nonce;
        result.factor = factor;
        result.modulus = modulus;
        result.exponent = exponent;
        result.blindedValue = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(toFixed(blinded, modulusBytes(modulus)));
        return result;
    }

    /**
     * 去盲并把服务端返回的签名拼成令牌：{@code nonce.signature}（Base64URL）。
     */
    public static String unblind(Blinded blinded, String blindedSignatureBase64) {
        BigInteger signature = new BigInteger(1,
            Base64.getUrlDecoder().decode(blindedSignatureBase64));
        BigInteger unblinded = signature.multiply(blinded.factor.modInverse(blinded.modulus))
            .mod(blinded.modulus);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(blinded.nonce);
        String sig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(toFixed(unblinded, modulusBytes(blinded.modulus)));
        return nonce + "." + sig;
    }

    /**
     * 本地自检：s^e mod n 必须等于 FDH(nonce)。
     */
    public static boolean verify(String token, String publicKeyBase64) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(token.substring(0, dot));
            BigInteger signature = new BigInteger(1,
                Base64.getUrlDecoder().decode(token.substring(dot + 1)));
            RSAPublicKey key = parse(publicKeyBase64);
            BigInteger modulus = key.getModulus();
            if (nonce.length != NONCE_BYTES || signature.compareTo(modulus) >= 0) {
                return false;
            }
            return signature.modPow(key.getPublicExponent(), modulus)
                .equals(fdh(nonce, modulus));
        } catch (Exception e) {
            return false;
        }
    }

    private static RSAPublicKey parse(String publicKeyBase64) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(publicKeyBase64)));
    }

    private static BigInteger fdh(byte[] nonce, BigInteger modulus) {
        int k = modulusBytes(modulus);
        for (int counter = 0; counter < 1024; counter++) {
            byte[] wide = expand(nonce, counter, k);
            BigInteger candidate = new BigInteger(1, wide);
            if (candidate.compareTo(modulus) < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("FDH 拒绝采样次数异常");
    }

    private static byte[] expand(byte[] nonce, int counter, int length) {
        byte[] out = new byte[length];
        int offset = 0;
        int block = 0;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while (offset < length) {
                digest.reset();
                digest.update(INFO.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(nonce);
                digest.update(intToBytes(counter));
                digest.update(intToBytes(block));
                byte[] part = digest.digest();
                int copy = Math.min(part.length, length - offset);
                System.arraycopy(part, 0, out, offset, copy);
                offset += copy;
                block++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
        return out;
    }

    private static int modulusBytes(BigInteger modulus) {
        return (modulus.bitLength() + 7) / 8;
    }

    private static byte[] toFixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] out = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
        return out;
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    /**
     * 致盲结果。
     */
    public static final class Blinded {
        private byte[] nonce;
        private BigInteger factor;
        private BigInteger modulus;
        private BigInteger exponent;
        private String blindedValue;

        public String getBlindedValue() {
            return blindedValue;
        }
    }
}
