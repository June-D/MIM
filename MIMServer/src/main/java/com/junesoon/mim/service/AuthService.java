package com.junesoon.mim.service;

import com.junesoon.mim.requests.JwtAuthenticationRequest;

public interface AuthService {

    String login(JwtAuthenticationRequest authenticationRequest) throws Exception;

    void register(JwtAuthenticationRequest request);

    /**
     * 用户名是否已被占用。查的是数据库，多实例下结果一致。
     */
    boolean usernameTaken(String username);
}
