package com.junesoon.mim.service;

import com.junesoon.mim.requests.PasswordChangeRequest;
import com.junesoon.mim.responses.PasswordChangeResult;

/**
 * 修改登录密码。
 */
public interface PasswordService {

    /**
     * 校验原密码、更新 BCrypt，并使该用户所有设备的旧 JWT 失效，
     * 然后为当前设备签发新令牌。
     */
    PasswordChangeResult change(String username, String deviceId, PasswordChangeRequest request)
        throws Exception;
}
