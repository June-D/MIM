package com.junesoon.mim.utils;

import com.junesoon.mim.config.KeyConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 服务端敏感字段的加密存储。
 *
 * <p>目前用于找回密码邮箱与 TOTP 密钥：AES-256-GCM，AAD 绑定用户名与字段类型，
 * 防止密文被跨账号/跨字段搬运。密钥优先取 {@code MIM_EMAIL_KEY}
 * （Base64 32 字节）；未配置时从 JWT 私钥派生，保证自建环境开箱可用。
 * 注意：回退派生意味着只拿到数据库、没拿到 JWT 私钥时仍无法解密；
 * 若攻击者同时拿到两者，则仍可解密，生产环境建议显式配置独立密钥。
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

    @Value("${mim.security.email-key-file:}")
    private String configuredKeyFile;

    @Value("${mim.security.email-key-previous:}")
    private String previousKey;

    @Value("${mim.security.email-key-previous-file:}")
    private String previousKeyFile;

    @Value("${mim.security.email-key-command:}")
    private String configuredKeyCommand;

    @Value("${mim.security.email-key-previous-command:}")
    private String previousKeyCommand;

    private volatile byte[] cachedKey;

    private volatile byte[] cachedPreviousKey;

    private volatile byte[] cachedLegacyKey;

    public EmailCryptoService(KeyConfiguration keyConfiguration) {
        this.keyConfiguration = keyConfiguration;
    }

    public String encrypt(String username, String email) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        return encryptValue("MIM/email/v1|", "邮箱", username, email.trim());
    }

    /**
     * 解密失败返回 null（密钥更换或数据损坏），不影响登录与其它功能。
     */
    public String decrypt(String username, String stored) {
        return decryptValue("MIM/email/v1|", "邮箱", username, stored);
    }

    /**
     * 加密 TOTP 共享密钥，避免数据库泄露后直接拿到可生成验证码的种子。
     */
    public String encryptTotp(String username, String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return null;
        }
        return encryptValue("MIM/totp/v1|", "二次验证密钥", username, secret.trim());
    }

    /**
     * 解密 TOTP 密钥。旧数据是未加密的 Base32（不含冒号），按原样返回以便平滑升级。
     */
    public String decryptTotp(String username, String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        if (stored.indexOf(':') <= 0) {
            return stored.trim();
        }
        return decryptValue("MIM/totp/v1|", "二次验证密钥", username, stored);
    }

    /**
     * 是否需要在启动时扫描并重加密旧数据。
     */
    public boolean rotationConfigured() {
        return StringUtils.hasText(
                SecretValue.load(configuredKey, configuredKeyFile, configuredKeyCommand))
            || StringUtils.hasText(
                SecretValue.load(previousKey, previousKeyFile, previousKeyCommand));
    }

    /**
     * 如邮箱密文不是当前密钥加密的，则用当前密钥重加密。
     * 返回 null 表示当前/上一把/旧 JWT 派生密钥都解不开，调用方应保留原值并告警。
     */
    public String reencryptEmail(String username, String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return stored;
        }
        try {
            if (decryptWith(key(), "MIM/email/v1|", username, stored) != null) {
                return stored;
            }
        } catch (Exception ignored) {
            // 继续尝试旧密钥
        }
        String plain = decryptAny("MIM/email/v1|", "邮箱", username, stored);
        return plain == null ? null : encryptValue("MIM/email/v1|", "邮箱", username, plain);
    }

    /**
     * TOTP 旧数据可能是明文 Base32，也可能是旧密钥密文；统一升级为当前密钥密文。
     */
    public String reencryptTotp(String username, String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return stored;
        }
        if (stored.indexOf(':') <= 0) {
            return encryptTotp(username, stored);
        }
        try {
            if (decryptWith(key(), "MIM/totp/v1|", username, stored) != null) {
                return stored;
            }
        } catch (Exception ignored) {
            // 继续尝试旧密钥
        }
        String plain = decryptAny("MIM/totp/v1|", "二次验证密钥", username, stored);
        return plain == null ? null : encryptTotp(username, plain);
    }

    private String encryptValue(String aadPrefix, String label, String username, String value) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(aadPrefix, username));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":"
                + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("加密" + label + "失败", e);
        }
    }

    private String decryptValue(String aadPrefix, String label, String username, String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        if (stored.indexOf(':') <= 0) {
            return null;
        }
        return decryptAny(aadPrefix, label, username, stored);
    }

    /**
     * 依次尝试当前、上一把、旧 JWT 派生密钥；认证失败会自然抛异常。
     */
    private String decryptAny(String aadPrefix, String label, String username, String stored) {
        List<byte[]> keys = new ArrayList<>(3);
        addKey(keys, safeCurrentKey());
        addKey(keys, previousKeyOrNull());
        addKey(keys, legacyKeyOrNull());
        Exception last = null;
        for (byte[] candidate : keys) {
            try {
                return decryptWith(candidate, aadPrefix, username, stored);
            } catch (Exception e) {
                last = e;
            }
        }
        log.warn("解密用户 {} 的{}失败：{}", username, label,
            last == null ? "没有可用密钥" : last.getMessage());
        return null;
    }

    private void addKey(List<byte[]> keys, byte[] candidate) {
        if (candidate == null) {
            return;
        }
        for (byte[] existing : keys) {
            if (Arrays.equals(existing, candidate)) {
                return;
            }
        }
        keys.add(candidate);
    }

    private byte[] safeCurrentKey() {
        try {
            return key();
        } catch (Exception e) {
            log.warn("邮箱加密密钥不可用：{}", e.getMessage());
            return null;
        }
    }

    private String decryptWith(byte[] aesKey, String aadPrefix, String username, String stored)
        throws Exception {
        int idx = stored.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        byte[] nonce = Base64.getDecoder().decode(stored.substring(0, idx));
        byte[] ciphertext = Base64.getDecoder().decode(stored.substring(idx + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
            new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(aadPrefix, username));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
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
            String configured = SecretValue.load(configuredKey, configuredKeyFile,
                configuredKeyCommand);
            if (configured != null && !configured.trim().isEmpty()) {
                byte[] decoded = Base64.getDecoder().decode(configured.trim());
                if (decoded.length != 32) {
                    throw new IllegalStateException("MIM_EMAIL_KEY 必须是 32 字节的 Base64");
                }
                cachedKey = decoded;
            } else {
                cachedKey = legacyKey();
                if (cachedKey == null) {
                    throw new IllegalStateException("邮箱加密密钥尚未初始化");
                }
            }
            return cachedKey;
        }
    }

    private byte[] previousKeyOrNull() {
        if (!StringUtils.hasText(
            SecretValue.load(previousKey, previousKeyFile, previousKeyCommand))) {
            return null;
        }
        byte[] key = cachedPreviousKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedPreviousKey != null) {
                return cachedPreviousKey;
            }
            byte[] decoded = Base64.getDecoder().decode(
                SecretValue.load(previousKey, previousKeyFile, previousKeyCommand).trim());
            if (decoded.length != 32) {
                throw new IllegalStateException(
                    "MIM_EMAIL_KEY_PREVIOUS 必须是 32 字节的 Base64");
            }
            cachedPreviousKey = decoded;
            return cachedPreviousKey;
        }
    }

    private byte[] legacyKeyOrNull() {
        try {
            return legacyKey();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] legacyKey() throws Exception {
        byte[] key = cachedLegacyKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedLegacyKey != null) {
                return cachedLegacyKey;
            }
            byte[] privateKey = keyConfiguration.getUserPriKey();
            if (privateKey == null || privateKey.length == 0) {
                return null;
            }
            cachedLegacyKey = MessageDigest.getInstance("SHA-256").digest(privateKey);
            return cachedLegacyKey;
        }
    }

    private byte[] aad(String prefix, String username) {
        return (prefix + username).getBytes(StandardCharsets.UTF_8);
    }
}
