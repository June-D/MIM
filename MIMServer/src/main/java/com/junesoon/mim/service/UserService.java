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
     * 绑定 / 更换 / 解绑找回邮箱。
     *
     * <p>必须校验当前密码：改找回通道等于改账号的恢复路径，
     * 只凭令牌不够。邮箱为空串时表示解绑。
     */
    void bindEmail(String username, String password, String email);

    /**
     * 在线心跳续期。
     */
    boolean heartbeat(String username);
}
