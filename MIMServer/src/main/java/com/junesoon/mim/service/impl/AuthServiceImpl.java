package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.BaseNotify;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.exception.UsernameRepeatException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.service.AuthService;
import com.junesoon.mim.service.PushService;
import com.junesoon.mim.service.RecoveryCodeService;
import com.junesoon.mim.service.UserService;
import com.junesoon.mim.utils.EmailCryptoService;
import com.junesoon.mim.utils.JwtTokenUtil;
import com.junesoon.mim.utils.TotpVerifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserService userService;

    @Resource
    private JwtTokenUtil jwtTokenUtil;

    @Resource
    private DeviceMapper deviceMapper;

    @Resource
    private TotpVerifier totpVerifier;

    @Resource
    private EmailCryptoService emailCryptoService;

    @Resource
    private PushService pushService;

    @Resource
    private RecoveryCodeService recoveryCodeService;

    @Override
    public String login(JwtAuthenticationRequest authenticationRequest, String deviceId)
        throws Exception {
        UserInfo info = userService.validate(authenticationRequest);
        if (!StringUtils.isEmpty(info.getId())) {
            // 密码通过后再校验 TOTP；缺少验证码时用专用状态码提示客户端弹窗
            User user = userService.getUserByUsername(info.getUsername());
            if (user != null && Boolean.TRUE.equals(user.getTotpEnabled())) {
                String code = authenticationRequest.getCode();
                if (!StringUtils.hasText(code)) {
                    throw new UserInvalidException("请输入二次验证码或恢复码",
                        CommonConstants.EX_2FA_REQUIRED_CODE);
                }
                String value = code.trim();
                if (value.matches("\\d{6}")) {
                    String secret = emailCryptoService.decryptTotp(user.getUsername(),
                        user.getTotpSecret());
                    if (!StringUtils.hasText(secret)
                        || !totpVerifier.verifyAndConsume(user.getUsername(), secret, value)) {
                        throw new UserInvalidException("二次验证码错误或已被使用");
                    }
                } else if (!recoveryCodeService.consume(user.getUsername(), value)) {
                    // 丢失 TOTP 设备时，使用启用时保存的一次性恢复码登录
                    throw new UserInvalidException("二次验证码或恢复码错误");
                }
            }
            int tokenVersion = 0;
            if (StringUtils.hasText(deviceId)) {
                Device device = deviceMapper.selectOne(info.getUsername(), deviceId);
                if (device != null) {
                    // 注销时 token_version 已自增：旧令牌版本对不上，自动失效。
                    // 允许重新登录并重新注册该设备，但旧令牌不会再复活。
                    tokenVersion = device.getTokenVersion() == null
                        ? 0 : device.getTokenVersion();
                }
            }
            JWTInfo jwtInfo = new JWTInfo(info.getUsername(), info.getId() + "",
                info.getNickname(), StringUtils.hasText(deviceId) ? deviceId : null,
                tokenVersion, UUID.randomUUID().toString(), System.currentTimeMillis());
            String token = jwtTokenUtil.generateToken(jwtInfo);
            notifyOtherDevices(info.getUsername(), deviceId);
            return token;
        }
        throw new UserInvalidException("用户不存在或账户密码错误!");
    }

    /**
     * 给该账号的其他在线设备发一条安全提醒。推送失败不影响登录。
     */
    private void notifyOtherDevices(String username, String deviceId) {
        if (pushService == null) {
            return;
        }
        try {
            String current = StringUtils.hasText(deviceId) ? deviceId : "default";
            Map<String, Object> data = new HashMap<>();
            data.put("event", "login");
            data.put("deviceId", current);
            data.put("time", System.currentTimeMillis());
            pushService.pushToUserExcept(username, current,
                BaseNotify.of(CommonConstants.NOTIFY_TYPE_SECURITY_ALERT, data));
        } catch (Exception e) {
            log.debug("发送登录安全提醒失败：{}", e.getMessage());
        }
    }

    @Override
    public void register(JwtAuthenticationRequest request) {
        if (!Boolean.TRUE.equals(request.getAgreedTerms())) {
            throw new UserInvalidException("请先阅读并同意用户协议");
        }
        if (!CommonConstants.TERMS_VERSION.equals(request.getTermsVersion())) {
            throw new UserInvalidException("用户协议版本已更新，请刷新后重试");
        }
        User user = userService.getUserByUsername(request.getUsername());
        if (user != null) {
            throw new UsernameRepeatException("用户名已存在");
        }
        userService.register(request);
    }

    @Override
    public boolean usernameTaken(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        return userService.getUserByUsername(username.trim()) != null;
    }
}
