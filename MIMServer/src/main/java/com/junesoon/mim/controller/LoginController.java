package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.auth.TokenService;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.AuthService;
import com.junesoon.mim.service.DeviceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: MIMServer
 * @description: 登录相关接口
 * @version: 1.0
 * @create: 2024-03-05 18:36
 */
@RestController
@RequestMapping("login")
@Slf4j
public class LoginController {

    private static final String DEVICE_PATTERN = "^[A-Za-z0-9._-]{1,64}$";

    @Resource
    private AuthService authService;

    @Resource
    private DeviceService deviceService;

    @Resource
    private TokenService tokenService;

    /**
     * 登录并签发令牌。
     *
     * <p>请求头带 {@code X-Device-Id} 时，令牌会绑定到该设备：服务端后续
     * 以令牌里的设备为准，不再相信可被随意替换的请求头。
     */
    @RequestMapping(value = "token", method = RequestMethod.POST)
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<String> login(
        @RequestBody JwtAuthenticationRequest authenticationRequest,
        @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader)
        throws Exception {
        String deviceId = normalizeDevice(deviceHeader);
        log.info(authenticationRequest.getUsername() + " require logging...");
        final String token = authService.login(authenticationRequest, deviceId);
        return new ObjectRestResponse<>().data(token);
    }

    /**
     * 登出：自增当前设备的令牌版本，使该设备已签发的 JWT 立即失效。
     *
     * <p>这样即使令牌此前已被复制走，登出后也无法继续使用。
     */
    @RequestMapping(value = "logout", method = RequestMethod.POST)
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> logout(@CurrentUser String username,
        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String deviceId = tokenService.deviceIdOf(authorization);
        if (StringUtils.hasText(deviceId)) {
            deviceService.invalidateTokens(username, deviceId);
        }
        return new ObjectRestResponse<Boolean>().data(true).rel(true);
    }

    private String normalizeDevice(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String device = header.trim();
        if (!device.matches(DEVICE_PATTERN)) {
            throw new UserInvalidException("设备标识只能包含字母、数字、点、下划线、短横线，最长 64 位");
        }
        return device;
    }
}
