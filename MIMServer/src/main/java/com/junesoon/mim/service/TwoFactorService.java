package com.junesoon.mim.service;

import com.junesoon.mim.responses.PasswordChangeResult;
import com.junesoon.mim.responses.TwoFactorEnableResult;
import com.junesoon.mim.responses.TwoFactorSetupResponse;

/**
 * TOTP 二次验证管理。
 */
public interface TwoFactorService {

    /**
     * 生成待确认的 TOTP 密钥。
     */
    TwoFactorSetupResponse setup(String username);

    /**
     * 校验验证码后启用，并使该用户所有设备的旧 JWT 失效。
     * 返回恢复码（只显示一次）。
     */
    TwoFactorEnableResult enable(String username, String deviceId, String code) throws Exception;

    /**
     * 校验当前口令与验证码后关闭二次验证。
     */
    PasswordChangeResult disable(String username, String deviceId, String password, String code)
        throws Exception;

    boolean isEnabled(String username);
}
