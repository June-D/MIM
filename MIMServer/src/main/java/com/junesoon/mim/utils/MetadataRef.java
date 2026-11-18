package com.junesoon.mim.utils;

import com.junesoon.mim.config.KeyConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 消息元数据的盲引用。
 *
 * <p>sealed 消息不保存明文发信人/设备，但历史、撤回、未读和会话清除仍需要
 * 一个稳定的索引键。这里用服务端密钥对用户名/设备做 HMAC-SHA256，数据库里
 * 只出现 64 位十六进制引用；没有服务端密钥的库无法反查用户。
 *
 * <p>密钥优先取 {@code MIM_SECURITY_METADATA_KEY}（Base64 32 字节），
 * 未配置时退化为 JWT 私钥。JWT 私钥轮换会导致旧 sealed 消息的历史索引失效，
 * 轮换前需要先回填或保留旧密钥；当前项目没有 JWT 轮换流程。
 */
@Slf4j
@Component
public class MetadataRef {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${mim.security.metadata-key:}")
    private String configuredKey;

    @Value("${mim.security.metadata-key-file:}")
    private String configuredKeyFile;

    @Value("${mim.security.metadata-key-command:}")
    private String configuredKeyCommand;

    @Autowired
    private KeyConfiguration keyConfiguration;

    private volatile byte[] cachedKey;
    private volatile boolean fallbackLogged;

    public boolean isEnabled() {
        try {
            key();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public String userRef(String username) {
        return hmac("user\u0000" + (username == null ? "" : username));
    }

    public String deviceRef(String username, String deviceId) {
        return hmac("device\u0000" + (username == null ? "" : username) + "\u0000"
            + (deviceId == null ? "" : deviceId));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key(), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("生成消息元数据盲引用失败", e);
        }
    }

    private byte[] key() {
        byte[] cached = cachedKey;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey;
            }
            String configured = SecretValue.load(configuredKey, configuredKeyFile,
                configuredKeyCommand);
            if (StringUtils.hasText(configured)) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(configured.trim());
                    if (decoded.length != 32) {
                        throw new IllegalStateException(
                            "MIM_SECURITY_METADATA_KEY 必须是 32 字节的 Base64");
                    }
                    cachedKey = decoded;
                    return cachedKey;
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                        "MIM_SECURITY_METADATA_KEY 不是合法的 Base64", e);
                }
            }
            byte[] privateKey = keyConfiguration == null ? null : keyConfiguration.getUserPriKey();
            if (privateKey == null || privateKey.length == 0) {
                throw new IllegalStateException("sealed sender 缺少元数据引用密钥");
            }
            if (!fallbackLogged) {
                fallbackLogged = true;
                log.warn("未配置 MIM_SECURITY_METADATA_KEY，消息元数据盲引用改用 JWT 私钥派生；"
                    + "JWT 私钥轮换会使旧 sealed 历史索引失效");
            }
            cachedKey = privateKey;
            return cachedKey;
        }
    }
}
