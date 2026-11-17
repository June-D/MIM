package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.exception.UsernameRepeatException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.service.AuthService;
import com.junesoon.mim.service.UserService;
import com.junesoon.mim.utils.JwtTokenUtil;
import com.junesoon.mim.utils.TotpUtil;
import java.util.UUID;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserService userService;

    @Resource
    private JwtTokenUtil jwtTokenUtil;

    @Resource
    private DeviceMapper deviceMapper;

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
                    throw new UserInvalidException("请输入二次验证码",
                        CommonConstants.EX_2FA_REQUIRED_CODE);
                }
                if (!TotpUtil.verify(user.getTotpSecret(), code)) {
                    throw new UserInvalidException("二次验证码错误");
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
            return jwtTokenUtil.generateToken(jwtInfo);
        }
        throw new UserInvalidException("用户不存在或账户密码错误!");
    }

    @Override
    public void register(JwtAuthenticationRequest request) {
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
