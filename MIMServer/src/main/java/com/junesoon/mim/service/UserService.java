package com.junesoon.mim.service;

import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.requests.JwtAuthenticationRequest;

public interface UserService {

    UserInfo validate(JwtAuthenticationRequest request);

    User getUserByUsername(String username);

    /**
     * 按用户名取用户信息（用户名已由鉴权层从 JWT 中解析）。
     */
    UserInfo getUserInfo(String username);

    void register(JwtAuthenticationRequest request);

    /**
     * 在线心跳续期。
     */
    boolean heartbeat(String username);
}
