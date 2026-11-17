package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.requests.PasswordChangeRequest;
import com.junesoon.mim.responses.PasswordChangeResult;
import com.junesoon.mim.service.PasswordService;
import com.junesoon.mim.utils.JwtTokenUtil;
import com.junesoon.mim.utils.ValidationUtil;
import com.junesoon.mim.websocket.SessionRegistry;
import java.util.Date;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 修改登录密码。
 *
 * <p>服务端只保存 Base64 口令的 BCrypt；本地密钥由客户端用原始口令重新加密。
 * 改密时自增该用户所有设备的令牌版本，并关闭本机 WebSocket 连接，
 * 让其它设备立即掉线、旧 JWT 立即失效。
 */
@Service
public class PasswordServiceImpl implements PasswordService {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final JwtTokenUtil jwtTokenUtil;
    private final SessionRegistry sessionRegistry;

    private final BCryptPasswordEncoder encoder =
        new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT);

    public PasswordServiceImpl(UserMapper userMapper, DeviceMapper deviceMapper,
        JwtTokenUtil jwtTokenUtil, SessionRegistry sessionRegistry) {
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.jwtTokenUtil = jwtTokenUtil;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordChangeResult change(String username, String deviceId,
        PasswordChangeRequest request) throws Exception {
        ValidationUtil.validateUsername(username);
        if (request == null || !StringUtils.hasText(request.getOldPassword())
            || !StringUtils.hasText(request.getNewPassword())) {
            throw new UserInvalidException("原密码和新密码都不能为空");
        }
        ValidationUtil.validatePassword(request.getNewPassword());

        User user = userMapper.selectOne(username);
        if (user == null) {
            throw new UserInvalidException("用户不存在");
        }
        if (!encoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new UserInvalidException("原密码不正确");
        }
        if (encoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new UserInvalidException("新密码不能与原密码相同");
        }

        user.setPassword(encoder.encode(request.getNewPassword()));
        user.setUpdTime(new Date());
        userMapper.updateByPrimaryKeySelective(user);

        // 所有设备的旧令牌一起失效；当前设备拿新版本重新签发
        deviceMapper.bumpAllTokenVersions(username);
        int tokenVersion = 0;
        if (StringUtils.hasText(deviceId)) {
            Device device = deviceMapper.selectOne(username, deviceId);
            if (device != null) {
                tokenVersion = device.getTokenVersion() == null ? 0 : device.getTokenVersion();
            }
        }
        // 关闭该用户在本实例上的所有 WS 连接；客户端会用新令牌重连
        sessionRegistry.closeUser(username);

        JWTInfo info = new JWTInfo(username, user.getId() + "", user.getNickname(),
            StringUtils.hasText(deviceId) ? deviceId : null, tokenVersion,
            UUID.randomUUID().toString(), System.currentTimeMillis());
        return new PasswordChangeResult(jwtTokenUtil.generateToken(info));
    }
}
