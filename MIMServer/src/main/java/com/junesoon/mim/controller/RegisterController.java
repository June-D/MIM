package com.junesoon.mim.controller;

import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.AuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: MIMServer
 * @description: 注册相关接口
 * @version: 1.0
 * @create: 2024-03-05 18:36
 */
@RestController
@RequestMapping("register")
@Slf4j
public class RegisterController {

    @Resource
    private AuthService authService;

    @RequestMapping(value = "token", method = RequestMethod.POST)
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<String> register(
        @RequestBody JwtAuthenticationRequest authenticationRequest) throws Exception {
        log.info(authenticationRequest.getUsername() + " require register...");
        authService.register(authenticationRequest);
        return new ObjectRestResponse<>();
    }

    /**
     * 注册页离开用户名输入框时调用。始终 HTTP 200：占用时 data 为 false。
     */
    @RequestMapping(value = "available", method = RequestMethod.GET)
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<Boolean> available(@RequestParam("username") String username) {
        boolean taken = authService.usernameTaken(username);
        ObjectRestResponse<Boolean> body = new ObjectRestResponse<>();
        body.setData(!taken);
        body.setRel(true);
        if (taken) {
            body.setMessage("用户名已存在");
        }
        return body;
    }
}
