package com.junesoon.mim.service;

import com.junesoon.mim.requests.JwtAuthenticationRequest;

public interface AuthService {

    /**
     * 兼容旧调用：签发未绑定设备的令牌。仅用于过渡期。
     */
    default String login(JwtAuthenticationRequest authenticationRequest) throws Exception {
        return login(authenticationRequest, null);
    }

    /**
     * 登录并签发绑定到指定设备的令牌。
     *
     * @param deviceId 登录请求携带的设备标识；为空表示未绑定（兼容旧客户端）
     */
    String login(JwtAuthenticationRequest authenticationRequest, String deviceId) throws Exception;

    void register(JwtAuthenticationRequest request);

    /**
     * 用户名是否已被占用。查的是数据库，多实例下结果一致。
     */
    boolean usernameTaken(String username);
}
