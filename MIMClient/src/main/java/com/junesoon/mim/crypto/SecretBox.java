package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地敏感数据的密封盒：口令派生密钥 + AES-256-GCM。
 *
 * <p>用途：把私钥、会话状态等落盘内容加密。即使文件被拷走，没有登录口令也解不开。
 * PBKDF2-HMAC-SHA256 迭代 12 万次，salt 每次随机。
 */
public final class SecretBox {

    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 12;

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private SecretBox() {
    }

    /**
     * 密封结果：salt / nonce / 密文三段都要一并保存。
     */
    public static final class Sealed {
        private final byte[] salt;
        private final byte[] nonce;
        private final byte[] ciphertext;

        public Sealed(byte[] salt, byte[] nonce, byte[] ciphertext) {
            this.salt = salt;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }

        public byte[] getSalt() {
            return salt;
        }

        public byte[] getNonce() {
            return nonce;
        }

        public byte[] getCiphertext() {
            return ciphertext;
        }
    }

    /**
     * 用口令加密明文。
     *
     * @param aad 附加认证数据，通常传所属对象标识（如用户名），防止密文跨对象搬运
     */
    public static Sealed seal(String password, String aad, byte[] plaintext) {
        byte[] salt = DhKeyUtil.randomBytes(SALT_BYTES);
        byte[] nonce = DhKeyUtil.randomBytes(NONCE_BYTES);
        byte[] wrappingKey = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return new Sealed(salt, nonce, cipher.doFinal(plaintext));
        } catch (Exception e) {
            throw new IllegalStateException("加密本地数据失败", e);
        } finally {
            Hkdf.wipe(wrappingKey);
        }
    }

    /**
     * 用口令解密。口令错误或密文被篡改都会抛 {@link IdentityException}，
     * 调用方必须明确告知用户，不能静默重建（否则历史消息永久丢失）。
     */
    public static byte[] open(String password, String aad, byte[] salt, byte[] nonce, byte[] ciphertext)
        throws IdentityException {
        byte[] wrappingKey = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IdentityException("本地数据解锁失败，可能是登录口令已变更");
        } finally {
            Hkdf.wipe(wrappingKey);
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        } finally {
            spec.clearPassword();
        }
    }
}
