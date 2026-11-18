package com.junesoon.mim.config;

import com.junesoon.mim.utils.EmailCryptoService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 邮箱/TOTP 密钥轮换：新密钥作为当前密钥、旧密钥作为 previous 配置后，
 * 启动时把旧密文（以及旧 JWT 私钥派生密钥加密的数据）重加密为当前密钥。
 *
 * <p>在 AuthServerRunner（加载 JWT 私钥）之后运行，保证旧回退密钥可用。
 * 单行失败只告警，不阻止启动；运维应根据日志处理失败行。
 */
@Slf4j
@Component
@Order(20)
public class EmailKeyRotation implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final EmailCryptoService emailCryptoService;

    public EmailKeyRotation(JdbcTemplate jdbcTemplate, EmailCryptoService emailCryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailCryptoService = emailCryptoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!emailCryptoService.rotationConfigured()) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                "select username, email_cipher, totp_secret from t_register_users");
        } catch (Exception e) {
            log.warn("邮箱密钥轮换跳过：读取用户表失败 {}", e.getMessage());
            return;
        }

        int updated = 0;
        int failed = 0;
        for (Map<String, Object> row : rows) {
            String username = value(row.get("username"));
            if (!StringUtils.hasText(username)) {
                continue;
            }
            String email = value(row.get("email_cipher"));
            String totp = value(row.get("totp_secret"));
            String newEmail = email;
            String newTotp = totp;
            if (StringUtils.hasText(email)) {
                String rotated = emailCryptoService.reencryptEmail(username, email);
                if (rotated == null) {
                    failed++;
                    log.warn("用户 {} 的邮箱无法用当前/上一把/旧 JWT 派生密钥解密，保留原值", username);
                } else {
                    newEmail = rotated;
                }
            }
            if (StringUtils.hasText(totp)) {
                String rotated = emailCryptoService.reencryptTotp(username, totp);
                if (rotated == null) {
                    failed++;
                    log.warn("用户 {} 的 TOTP 密钥无法用当前/上一把/旧 JWT 派生密钥解密，保留原值", username);
                } else {
                    newTotp = rotated;
                }
            }
            if (Objects.equals(email, newEmail) && Objects.equals(totp, newTotp)) {
                continue;
            }
            try {
                jdbcTemplate.update(
                    "update t_register_users set email_cipher = ?, totp_secret = ? where username = ?",
                    newEmail, newTotp, username);
                updated++;
            } catch (Exception e) {
                failed++;
                log.warn("用户 {} 的密钥重加密写回失败：{}", username, e.getMessage());
            }
        }
        if (updated > 0 || failed > 0) {
            log.info("邮箱/TOTP 密钥轮换完成：重加密 {} 行，失败 {} 个字段", updated, failed);
        }
    }

    private static String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
