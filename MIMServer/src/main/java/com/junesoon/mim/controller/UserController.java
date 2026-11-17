package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public UserController(UserService userService) {
        this.userService = userService;
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
}
