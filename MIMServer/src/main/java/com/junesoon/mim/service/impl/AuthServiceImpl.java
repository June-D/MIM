package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.exception.UsernameRepeatException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.service.AuthService;
import com.junesoon.mim.service.UserService;
import com.junesoon.mim.utils.JwtTokenUtil;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserService userService;

    @Resource
    private JwtTokenUtil jwtTokenUtil;

    @Override
    public String login(JwtAuthenticationRequest authenticationRequest) throws Exception {
        UserInfo info = userService.validate(authenticationRequest);
        if (!StringUtils.isEmpty(info.getId())) {
            return jwtTokenUtil.generateToken(new JWTInfo(info.getUsername(), info.getId() + "", info.getNickname()));
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
