package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.PasswordChangeRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.responses.PasswordChangeResult;
import com.junesoon.mim.service.PasswordService;
import com.junesoon.mim.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户相关接口。
 *
 * <p>用户名统一从 Authorization 头解析，不再把 JWT 放在 URL 路径上，
 * 避免凭证进入访问日志、代理日志与浏览器历史。
 */
@RestController
@RequestMapping("user")
@Slf4j
public class UserController {

    private final UserService userService;
    private final PasswordService passwordService;

    public UserController(UserService userService, PasswordService passwordService) {
        this.userService = userService;
        this.passwordService = passwordService;
    }

    @GetMapping("/info")
    public ObjectRestResponse<UserInfo> getUserInfo(@CurrentUser String username) {
        return new ObjectRestResponse<UserInfo>().data(userService.getUserInfo(username)).rel(true);
    }

    /**
     * 在线状态心跳。客户端定期调用或通过 WS ping 续期在线 TTL。
     */
    @PostMapping("/heartbeat")
    public ObjectRestResponse<Boolean> heartbeat(@CurrentUser String username) {
        return new ObjectRestResponse<Boolean>().data(userService.heartbeat(username)).rel(true);
    }

    /**
     * 修改登录密码。成功后该用户所有设备的旧 JWT 失效，当前设备拿到新令牌。
     */
    @PostMapping("/password")
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<PasswordChangeResult> changePassword(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody PasswordChangeRequest request)
        throws Exception {
        PasswordChangeResult result = passwordService.change(username, deviceId, request);
        log.info("用户 {} 设备 {} 修改了登录密码", username, deviceId);
        return new ObjectRestResponse<PasswordChangeResult>().data(result).rel(true);
    }
}
