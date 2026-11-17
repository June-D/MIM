package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.RecoveryService;
import com.junesoon.mim.utils.EmailCryptoService;
import com.junesoon.mim.utils.EmailSender;
import com.junesoon.mim.utils.ValidationUtil;
import com.junesoon.mim.websocket.SessionRegistry;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 邮箱找回密码。
 *
 * <p>验证码只存 BCrypt 哈希，10 分钟有效；同一账号 60 秒内只能发一次，
 * 最多尝试 5 次。验证码通过后重置密码、关闭 TOTP 并让该用户所有设备下线。
 * 验证码存 Redis，Redis 不可用时找回功能不可用（fail-closed）。
 */
@Slf4j
@Service
public class RecoveryServiceImpl implements RecoveryService {

    private static final int CODE_TTL_SECONDS = 600;
    private static final int SEND_INTERVAL_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final String CODE_PREFIX = "mim:recover:code:";
    private static final String ATTEMPT_PREFIX = "mim:recover:attempts:";
    private static final String SEND_PREFIX = "mim:recover:send:";

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final SessionRegistry sessionRegistry;
    private final StringRedisTemplate redisTemplate;
    private final EmailCryptoService emailCryptoService;
    private final EmailSender emailSender;
    private final BCryptPasswordEncoder encoder =
        new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT);
    private final SecureRandom random = new SecureRandom();

    public RecoveryServiceImpl(UserMapper userMapper, DeviceMapper deviceMapper,
        SessionRegistry sessionRegistry, StringRedisTemplate redisTemplate,
        EmailCryptoService emailCryptoService, EmailSender emailSender) {
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.sessionRegistry = sessionRegistry;
        this.redisTemplate = redisTemplate;
        this.emailCryptoService = emailCryptoService;
        this.emailSender = emailSender;
    }

    @Override
    public void sendCode(String username) {
        if (!emailSender.isConfigured()) {
            throw new UserInvalidException("邮件服务未配置，暂时无法通过邮箱找回密码");
        }
        if (!StringUtils.hasText(username)) {
            return;
        }
        User user = userMapper.selectOne(username);
        if (user == null) {
            return;
        }
        String email = emailCryptoService.decrypt(username, user.getEmailCipher());
        if (!StringUtils.hasText(email)) {
            return;
        }
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(
                SEND_PREFIX + username, "1", SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(fresh)) {
                return;
            }
        } catch (Exception e) {
            log.warn("找回密码发送频率检查失败：{}", e.getMessage());
            throw new UserInvalidException("服务暂不可用，请稍后再试");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        try {
            redisTemplate.opsForValue().set(CODE_PREFIX + username, encoder.encode(code),
                CODE_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.delete(ATTEMPT_PREFIX + username);
            emailSender.send(email, "MIM 密码找回验证码",
                "你的 MIM 密码找回验证码是：" + code + "\n\n"
                    + "10 分钟内有效。如果这不是你本人操作，请忽略本邮件。\n"
                    + "请不要把验证码告诉任何人。");
            log.info("已向用户 {} 的绑定邮箱发送找回密码验证码", username);
        } catch (Exception e) {
            try {
                redisTemplate.delete(SEND_PREFIX + username);
                redisTemplate.delete(CODE_PREFIX + username);
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
            log.warn("发送找回密码邮件失败：{}", e.getMessage());
            throw new UserInvalidException("邮件发送失败，请稍后再试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String username, String code, String newPassword) {
        ValidationUtil.validateUsername(username);
        ValidationUtil.validatePassword(newPassword);
        if (!StringUtils.hasText(code)) {
            throw new UserInvalidException("请输入邮箱验证码");
        }
        String storedHash;
        try {
            storedHash = redisTemplate.opsForValue().get(CODE_PREFIX + username);
        } catch (Exception e) {
            throw new UserInvalidException("服务暂不可用，请稍后再试");
        }
        if (storedHash == null) {
            throw new UserInvalidException("验证码错误或已过期");
        }
        try {
            Long attempts = redisTemplate.opsForValue().increment(ATTEMPT_PREFIX + username);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(ATTEMPT_PREFIX + username, CODE_TTL_SECONDS,
                    TimeUnit.SECONDS);
            }
            if (attempts != null && attempts > MAX_ATTEMPTS) {
                redisTemplate.delete(CODE_PREFIX + username);
                throw new UserInvalidException("尝试次数过多，请重新获取验证码");
            }
        } catch (UserInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new UserInvalidException("服务暂不可用，请稍后再试");
        }
        if (!encoder.matches(code.trim(), storedHash)) {
            throw new UserInvalidException("验证码错误");
        }

        User user = userMapper.selectOne(username);
        if (user == null) {
            throw new UserInvalidException("验证码错误或已过期");
        }
        user.setPassword(encoder.encode(newPassword));
        // 找回密码通常伴随丢失 2FA 设备：邮箱验证码通过后一并关闭二次验证
        user.setTotpSecret("");
        user.setTotpEnabled(false);
        user.setUpdTime(new Date());
        userMapper.updateByPrimaryKeySelective(user);

        deviceMapper.bumpAllTokenVersions(username);
        sessionRegistry.closeUser(username);
        try {
            redisTemplate.delete(CODE_PREFIX + username);
            redisTemplate.delete(ATTEMPT_PREFIX + username);
            redisTemplate.delete(SEND_PREFIX + username);
        } catch (Exception e) {
            log.debug("清理找回密码验证码失败：{}", e.getMessage());
        }
        log.info("用户 {} 通过邮箱验证码重置了密码并关闭二次验证", username);
    }
}
