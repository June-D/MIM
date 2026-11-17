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

    private volatile byte[] cachedKey;

    public boolean isEnabled() {
        return StringUtils.hasText(configuredKey);
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
        int idx = stored.indexOf(':', PREFIX.length());
        if (idx <= PREFIX.length()) {
            throw new IllegalStateException("加密的 JWT 密钥格式损坏");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(stored.substring(PREFIX.length(), idx));
            byte[] ciphertext = Base64.getDecoder().decode(stored.substring(idx + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(name));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                "JWT 密钥解密失败，请检查 MIM_MASTER_KEY 是否正确", e);
        }
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
            byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
            if (decoded.length != 32) {
                throw new IllegalStateException("MIM_MASTER_KEY 必须是 32 字节的 Base64");
            }
            cachedKey = decoded;
            return cachedKey;
        }
    }

    private byte[] aad(String name) {
        return ("MIM/rsa/v1|" + name).getBytes(StandardCharsets.UTF_8);
    }
}
