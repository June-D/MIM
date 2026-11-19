package com.junesoon.mim.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * 盲签名令牌的 RSA 全定义域哈希（FDH）与签验。
 *
 * <p>用途：客户端先「致盲」一个随机 nonce 再交给服务端签名，服务端因此
 * <b>看不到 nonce</b>，事后也无法把令牌与账号对应起来；客户端收到签名后
 * 去盲，得到可验证的令牌。这是 2c 匿名提交里唯一保护提交者身份的机制。
 *
 * <p>这不是教科书式的 RSA-PKCS#1 签名：盲签名必须对「模数大小」的
 * 全定义域哈希值直接做 RSA，因此这里用计数器模式把 SHA-256 扩展到
 * 模数字节数，并用拒绝采样保证结果小于 n。实现遵循同一份规范，
 * 客户端 {@code BlindRsaToken} 必须逐字节复现。
 *
 * <p>安全性说明：本实现是 2c 的第一版，尚未经过外部密码学审计；
 * 生产启用前应做一次独立评审（尤其要确认拒绝采样与 blinding factor
 * 的取样范围）。
 *
 * [INPUT]: 只依赖 JDK 的 BigInteger / MessageDigest / RSA 接口
 * [OUTPUT]: fdh / signBlinded / verify 三个静态方法
 * [POS]: SenderTokenServiceImpl 的签验原语；客户端有同构实现
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class BlindRsa {

    /** 参与哈希的域分隔串；改动即令牌格式不兼容。 */
    private static final String INFO = "MIM/SENDER-TOKEN/1";

    private BlindRsa() {
    }

    /**
     * 把 32 字节 nonce 映射到 [0, n) 的整数。
     */
    public static BigInteger fdh(byte[] nonce, BigInteger modulus) {
        int k = (modulus.bitLength() + 7) / 8;
        for (int counter = 0; counter < 1024; counter++) {
            byte[] wide = expand(nonce, counter, k);
            BigInteger candidate = new BigInteger(1, wide);
            if (candidate.compareTo(modulus) < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("FDH 拒绝采样次数异常，模数可能不合法");
    }

    /**
     * 计数器模式扩展：SHA256(INFO || nonce || counter || blockIndex)。
     */
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

    /**
     * 对致盲后的值做 RSA 私钥运算。值必须落在 [0, n) 内。
     */
    public static BigInteger signBlinded(BigInteger blinded, RSAPrivateKey key) {
        BigInteger modulus = key.getModulus();
        if (blinded.signum() < 0 || blinded.compareTo(modulus) >= 0) {
            throw new IllegalArgumentException("致盲值超出模数范围");
        }
        return blinded.modPow(key.getPrivateExponent(), modulus);
    }

    /**
     * 验证去盲后的签名：s^e mod n 必须等于 FDH(nonce)。
     */
    public static boolean verify(BigInteger signature, byte[] nonce, RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        if (signature.signum() < 0 || signature.compareTo(modulus) >= 0) {
            return false;
        }
        BigInteger expected = fdh(nonce, modulus);
        return signature.modPow(key.getPublicExponent(), modulus).equals(expected);
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }
}
