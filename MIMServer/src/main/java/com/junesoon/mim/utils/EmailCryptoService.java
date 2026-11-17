package com.junesoon.mim.utils;

import com.junesoon.mim.config.KeyConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 找回密码邮箱的加密存储。
 *
 * <p>邮箱只用于找回密码、仅本人可见，但仍按敏感数据对待：AES-256-GCM 加密，
 * AAD 绑定用户名，防止密文被跨账号搬运。密钥优先取 {@code MIM_EMAIL_KEY}
 * （Base64 32 字节）；未配置时从 JWT 私钥派生，保证自建环境开箱可用。
 */
@Slf4j
@Component
public class EmailCryptoService {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyConfiguration keyConfiguration;
    private final SecureRandom random = new SecureRandom();

    @Value("${mim.security.email-key:}")
    private String configuredKey;

    private volatile byte[] cachedKey;

    public EmailCryptoService(KeyConfiguration keyConfiguration) {
        this.keyConfiguration = keyConfiguration;
    }

    public String encrypt(String username, String email) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(username));
            byte[] ciphertext = cipher.doFinal(email.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":"
                + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("加密邮箱失败", e);
        }
    }

    /**
     * 解密失败返回 null（密钥更换或数据损坏），不影响登录与其它功能。
     */
    public String decrypt(String username, String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        int idx = stored.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(stored.substring(0, idx));
            byte[] ciphertext = Base64.getDecoder().decode(stored.substring(idx + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(username));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("解密用户 {} 的邮箱失败：{}", username, e.getMessage());
            return null;
        }
    }

    private byte[] key() throws Exception {
        byte[] key = cachedKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey;
            }
            if (configuredKey != null && !configuredKey.trim().isEmpty()) {
                byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
                if (decoded.length != 32) {
                    throw new IllegalStateException("MIM_EMAIL_KEY 必须是 32 字节的 Base64");
                }
                cachedKey = decoded;
            } else {
                byte[] privateKey = keyConfiguration.getUserPriKey();
                if (privateKey == null || privateKey.length == 0) {
                    throw new IllegalStateException("邮箱加密密钥尚未初始化");
                }
                cachedKey = MessageDigest.getInstance("SHA-256").digest(privateKey);
            }
            return cachedKey;
        }
    }

    private byte[] aad(String username) {
        return ("MIM/email/v1|" + username).getBytes(StandardCharsets.UTF_8);
    }
}
