package com.junesoon.mim.config;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时的安全配置自检。
 *
 * <p>默认只告警（本地开发需要便利），但必须让运维在日志里一眼看到
 * 「当前部署是否可以暴露到公网」。生产环境出现 WARN 就应该处理。
 * 设置 {@code MIM_REDIS_REQUIRE_SECURE=true} 后，非本机 Redis 缺密码或 TLS
 * 会直接拒绝启动。
 */
@Slf4j
@Component
@Order
public class SecurityStartupChecker implements CommandLineRunner {

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean redisSsl;

    @Value("${mim.redis.require-secure:false}")
    private boolean requireSecureRedis;

    @Value("${mim.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${mim.tls.block-plaintext:true}")
    private boolean blockPlaintext;

    @Value("${mim.rate-limit.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @Value("${mim.security.email-key:}")
    private String emailKey;

    @Value("${mim.security.email-key-file:}")
    private String emailKeyFile;

    @Value("${mim.security.email-key-command:}")
    private String emailKeyCommand;

    @Value("${mim.security.master-key:}")
    private String masterKey;

    @Value("${mim.security.master-key-file:}")
    private String masterKeyFile;

    @Value("${mim.security.master-key-command:}")
    private String masterKeyCommand;

    @Value("${mim.auth.require-device-binding:false}")
    private boolean requireDeviceBinding;

    @Override
    public void run(String... args) {
        boolean loopback = isLoopback(redisHost);
        boolean hasPassword = StringUtils.hasText(redisPassword);
        if (!loopback && (!hasPassword || !redisSsl)) {
            String missing = !hasPassword ? "密码" : "TLS";
            if (requireSecureRedis) {
                throw new IllegalStateException("Redis 位于非本机地址且缺少" + missing
                    + "，已按 MIM_REDIS_REQUIRE_SECURE=true 拒绝启动");
            }
            log.warn("安全自检：Redis 位于非本机地址且未启用{}。请配置 MIM_REDIS_PASSWORD"
                + " 与 MIM_REDIS_SSL，或设置 MIM_REDIS_REQUIRE_SECURE=true 强制检查。",
                missing);
        } else if (!hasPassword) {
            log.warn("安全自检：Redis 未配置密码。本机开发可接受，生产环境请设置"
                + " MIM_REDIS_PASSWORD，并限制 Redis 只监听内网/本机。");
        }
        if (!tlsEnabled) {
            log.warn("安全自检：TLS 未启用。生产环境必须设置 MIM_TLS_ENABLED=true，"
                + "否则登录口令与 JWT 会在网络中以明文传输。");
        } else if (!blockPlaintext) {
            log.warn("安全自检：已启用 TLS，但 mim.tls.block-plaintext=false，"
                + "明文端口仍可访问业务接口，TLS 可被绕过。");
        }
        if (trustForwardedHeaders) {
            log.warn("安全自检：已信任 X-Forwarded-For。请确认前置代理会覆盖客户端自带的"
                + "转发头，且只允许可信代理访问服务端。");
        }
        if (!StringUtils.hasText(emailKey) && !StringUtils.hasText(emailKeyFile)
            && !StringUtils.hasText(emailKeyCommand)) {
            log.warn("安全自检：未配置 MIM_EMAIL_KEY / MIM_EMAIL_KEY_FILE / "
                + "MIM_EMAIL_KEY_COMMAND，邮箱与 TOTP 密钥的加密密钥将从 JWT 私钥派生。"
                + "生产环境建议配置独立的 32 字节 Base64 密钥。");
        }
        if (!StringUtils.hasText(masterKey) && !StringUtils.hasText(masterKeyFile)
            && !StringUtils.hasText(masterKeyCommand)) {
            log.warn("安全自检：未配置 MIM_MASTER_KEY / MIM_MASTER_KEY_FILE / "
                + "MIM_MASTER_KEY_COMMAND，新生成的 JWT 私钥将明文存储；"
                + "若数据库中已有加密密钥则会拒绝启动。");
        }
        if (!requireDeviceBinding) {
            log.warn("安全自检：仍允许未绑定设备的旧 JWT。客户端全部升级后，"
                + "设置 MIM_AUTH_REQUIRE_DEVICE_BINDING=true 可强制旧令牌重新登录。");
        }
    }

    static boolean isLoopback(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "127.0.0.1".equals(value)
            || "::1".equals(value) || "[::1]".equals(value);
    }
}
