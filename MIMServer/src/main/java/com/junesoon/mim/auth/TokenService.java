package com.junesoon.mim.auth;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserTokenException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.utils.UserAuthUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 令牌校验：签名/过期之外，再核对设备绑定与设备令牌版本。
 *
 * <p>为什么需要设备版本：登出或注销设备后，旧 JWT 在自然过期前仍然可用。
 * 服务端把版本号写进令牌，登出/注销时自增，校验时比对一次数据库即可让旧令牌
 * 立即失效，不依赖 Redis（Redis 故障不会导致所有人被登出）。
 *
 * <p>未绑定设备的旧令牌（deviceId 为空）仍然放行，作为升级兼容期；
 * 新客户端登录都会带上设备头，签发的令牌全部绑定设备。
 */
@Slf4j
@Component
public class TokenService {

    /**
     * 请求级缓存键：同一请求里 @CurrentUser 与 @CurrentDevice 共用一次解析/查询。
     */
    public static final String REQUEST_ATTRIBUTE = "mim.jwt.info";

    private final UserAuthUtil userAuthUtil;
    private final DeviceMapper deviceMapper;

    public TokenService(UserAuthUtil userAuthUtil, DeviceMapper deviceMapper) {
        this.userAuthUtil = userAuthUtil;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 解析并校验令牌。失败抛 {@link UserTokenException}，由全局异常处理成 401。
     */
    public JWTInfo resolve(String token) throws Exception {
        JWTInfo info = userAuthUtil.getInfoFromToken(token);
        String deviceId = info.getDeviceId();
        if (!StringUtils.hasText(deviceId)) {
            return info;
        }
        Device device = deviceMapper.selectOne(info.getUsername(), deviceId);
        if (device == null) {
            // 登录后、设备注册前的窗口：令牌先签发，设备随后登记，允许通过
            return info;
        }
        if (Boolean.TRUE.equals(device.getRevoked())) {
            throw new UserTokenException("该设备已注销，请重新登录");
        }
        int current = device.getTokenVersion() == null ? 0 : device.getTokenVersion();
        if (current != info.getTokenVersion()) {
            throw new UserTokenException("登录已失效，请重新登录");
        }
        return info;
    }

    /**
     * 从 Authorization 头里取设备标识。解析失败返回 null，不抛异常。
     */
    public String deviceIdOf(String authorization) {
        String token = extractToken(authorization);
        if (token == null) {
            return null;
        }
        try {
            JWTInfo info = userAuthUtil.getInfoFromToken(token);
            return StringUtils.hasText(info.getDeviceId()) ? info.getDeviceId() : null;
        } catch (Exception e) {
            log.debug("解析登出令牌失败：{}", e.getMessage());
            return null;
        }
    }

    private String extractToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }
}
