package com.junesoon.mim.controller;

import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.AuthService;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Resource
    private AuthService authService;

    @RequestMapping(value = "token", method = RequestMethod.POST)
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<String> login(
        @RequestBody JwtAuthenticationRequest authenticationRequest) throws Exception {
        log.info(authenticationRequest.getUsername() + " require logging...");
        final String token = authService.login(authenticationRequest);
        return new ObjectRestResponse<>().data(token);
    }
}
