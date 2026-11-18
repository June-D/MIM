package com.junesoon.mim.service;

/**
 * 邮箱找回密码。
 */
public interface RecoveryService {

    /**
     * 向账号绑定的邮箱发送 6 位验证码。
     *
     * <p>账号不存在、未填写邮箱或刚发送过时静默返回，避免暴露账号信息。
     */
    void sendCode(String username);

    /**
     * 校验邮箱验证码并重置密码。成功后关闭二次验证并让所有旧令牌失效。
     */
    void resetPassword(String username, String code, String newPassword);

    /**
     * 使用一次性恢复码重置密码（邮箱不可用 / 未填邮箱时的后备）。
     * 成功后关闭二次验证、清空恢复码并让所有旧令牌失效。
     */
    void resetByRecoveryCode(String username, String code, String newPassword);
}
