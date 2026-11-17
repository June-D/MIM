package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.PasswordChangeResult;
import com.junesoon.mim.responses.TwoFactorSetupResponse;
import com.junesoon.mim.service.TwoFactorService;
import com.junesoon.mim.utils.JwtTokenUtil;
import com.junesoon.mim.utils.TotpUtil;
import com.junesoon.mim.websocket.SessionRegistry;
import java.util.Date;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * TOTP 二次验证。
 *
 * <p>服务端保存共享密钥（Base32），登录在密码校验通过后再校验 6 位验证码。
 * 启用/关闭都会自增该用户所有设备的令牌版本，并踢掉 WebSocket 连接，
 * 当前设备拿到新令牌；其它设备需要重新登录。
 */
@Service
public class TwoFactorServiceImpl implements TwoFactorService {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final JwtTokenUtil jwtTokenUtil;
    private final SessionRegistry sessionRegistry;

    private final BCryptPasswordEncoder encoder =
        new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT);

    public TwoFactorServiceImpl(UserMapper userMapper, DeviceMapper deviceMapper,
        JwtTokenUtil jwtTokenUtil, SessionRegistry sessionRegistry) {
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.jwtTokenUtil = jwtTokenUtil;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public TwoFactorSetupResponse setup(String username) {
        User user = requireUser(username);
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            throw new UserInvalidException("二次验证已启用，请先关闭");
        }
        String secret = TotpUtil.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(false);
        user.setUpdTime(new Date());
        userMapper.updateByPrimaryKeySelective(user);
        return new TwoFactorSetupResponse(secret, TotpUtil.otpauthUri(username, secret));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordChangeResult enable(String username, String deviceId, String code)
        throws Exception {
        User user = requireUser(username);
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            throw new UserInvalidException("二次验证已启用");
        }
        if (!StringUtils.hasText(user.getTotpSecret())) {
            throw new UserInvalidException("请先生成二次验证密钥");
        }
        if (!TotpUtil.verify(user.getTotpSecret(), code)) {
            throw new UserInvalidException("二次验证码错误");
        }
        user.setTotpEnabled(true);
        user.setUpdTime(new Date());
        userMapper.updateByPrimaryKeySelective(user);
        return revokeAllAndIssue(user, deviceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordChangeResult disable(String username, String deviceId, String password,
        String code) throws Exception {
        User user = requireUser(username);
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            throw new UserInvalidException("二次验证未启用");
        }
        if (!StringUtils.hasText(password) || !encoder.matches(password, user.getPassword())) {
            throw new UserInvalidException("当前密码不正确");
        }
        if (!TotpUtil.verify(user.getTotpSecret(), code)) {
            throw new UserInvalidException("二次验证码错误");
        }
        // 选择性更新不会把 null 写回数据库，用空串显式清空
        user.setTotpSecret("");
        user.setTotpEnabled(false);
        user.setUpdTime(new Date());
        userMapper.updateByPrimaryKeySelective(user);
        return revokeAllAndIssue(user, deviceId);
    }

    @Override
    public boolean isEnabled(String username) {
        User user = userMapper.selectOne(username);
        return user != null && Boolean.TRUE.equals(user.getTotpEnabled());
    }

    private User requireUser(String username) {
        User user = userMapper.selectOne(username);
        if (user == null) {
            throw new UserInvalidException("用户不存在");
        }
        return user;
    }

    private PasswordChangeResult revokeAllAndIssue(User user, String deviceId) throws Exception {
        deviceMapper.bumpAllTokenVersions(user.getUsername());
        int tokenVersion = 0;
        if (StringUtils.hasText(deviceId)) {
            Device device = deviceMapper.selectOne(user.getUsername(), deviceId);
            if (device != null) {
                tokenVersion = device.getTokenVersion() == null ? 0 : device.getTokenVersion();
            }
        }
        sessionRegistry.closeUser(user.getUsername());
        JWTInfo info = new JWTInfo(user.getUsername(), user.getId() + "", user.getNickname(),
            StringUtils.hasText(deviceId) ? deviceId : null, tokenVersion,
            UUID.randomUUID().toString(), System.currentTimeMillis());
        return new PasswordChangeResult(jwtTokenUtil.generateToken(info));
    }
}
