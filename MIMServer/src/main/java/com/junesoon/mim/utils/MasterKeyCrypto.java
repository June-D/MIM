package com.junesoon.mim.utils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 服务端主密钥：用于加密落库的 JWT RSA 私钥。
 *
 * <p>密钥取 {@code MIM_MASTER_KEY}（Base64 32 字节）。未配置时保持旧的明文存储，
 * 仅打印告警；配置后新写入的密钥用 AES-256-GCM 加密，AAD 绑定数据库中的键名。
 * 主密钥丢失或错误会导致服务端拒绝启动，不会静默换钥。
 */
@Slf4j
@Component
public class MasterKeyCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    @Value("${mim.security.master-key:}")
    private String configuredKey;

    @Value("${mim.security.master-key-file:}")
    private String configuredKeyFile;

    @Value("${mim.security.master-key-previous:}")
    private String previousKey;

    @Value("${mim.security.master-key-previous-file:}")
    private String previousKeyFile;

    @Value("${mim.security.master-key-command:}")
    private String configuredKeyCommand;

    @Value("${mim.security.master-key-previous-command:}")
    private String previousKeyCommand;

    private volatile byte[] cachedKey;

    private volatile byte[] cachedPreviousKey;

    public boolean isEnabled() {
        return StringUtils.hasText(
            SecretValue.load(configuredKey, configuredKeyFile, configuredKeyCommand));
    }

    public String encrypt(String name, String plaintext) {
        if (!isEnabled() || plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(name));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(nonce) + ":"
                + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("加密 JWT 密钥失败", e);
        }
    }

    public String decrypt(String name, String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        if (!isEnabled()) {
            throw new IllegalStateException(
                "数据库中存有加密的 JWT 密钥，但未配置 MIM_MASTER_KEY，拒绝启动");
        }
        try {
            return decryptWith(key(), name, stored);
        } catch (Exception currentFailure) {
            byte[] previous = previousKeyOrNull();
            if (previous != null) {
                try {
                    return decryptWith(previous, name, stored);
                } catch (Exception ignored) {
                    // 落到下面的统一错误
                }
            }
            throw new IllegalStateException(
                "JWT 密钥解密失败，请检查 MIM_MASTER_KEY / MIM_MASTER_KEY_PREVIOUS",
                currentFailure);
        }
    }

    /**
     * 如果当前密文是用上一把主密钥加密的，则用当前主密钥重加密。
     * 已经是当前密钥加密时原样返回；两把都解不开则失败。
     */
    public String reencryptIfNeeded(String name, String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        if (!isEnabled()) {
            throw new IllegalStateException(
                "数据库中存有加密的 JWT 密钥，但未配置 MIM_MASTER_KEY，拒绝启动");
        }
        try {
            decryptWith(key(), name, stored);
            return stored;
        } catch (Exception currentFailure) {
            byte[] previous = previousKeyOrNull();
            if (previous == null) {
                throw new IllegalStateException(
                    "JWT 密钥无法用当前主密钥解密，且未配置 MIM_MASTER_KEY_PREVIOUS",
                    currentFailure);
            }
            try {
                String plaintext = decryptWith(previous, name, stored);
                return encrypt(name, plaintext);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "JWT 密钥无法用当前或上一把主密钥解密", e);
            }
        }
    }

    private String decryptWith(byte[] aesKey, String name, String stored) throws Exception {
        int idx = stored.indexOf(':', PREFIX.length());
        if (idx <= PREFIX.length()) {
            throw new IllegalStateException("加密的 JWT 密钥格式损坏");
        }
        byte[] nonce = Base64.getDecoder().decode(stored.substring(PREFIX.length(), idx));
        byte[] ciphertext = Base64.getDecoder().decode(stored.substring(idx + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
            new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(name));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
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
            byte[] decoded = Base64.getDecoder().decode(
                SecretValue.load(configuredKey, configuredKeyFile, configuredKeyCommand).trim());
            if (decoded.length != 32) {
                throw new IllegalStateException("MIM_MASTER_KEY 必须是 32 字节的 Base64");
            }
            cachedKey = decoded;
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
                    "MIM_MASTER_KEY_PREVIOUS 必须是 32 字节的 Base64");
            }
            cachedPreviousKey = decoded;
            return cachedPreviousKey;
        }
    }

    private byte[] aad(String name) {
        return ("MIM/rsa/v1|" + name).getBytes(StandardCharsets.UTF_8);
    }
}
