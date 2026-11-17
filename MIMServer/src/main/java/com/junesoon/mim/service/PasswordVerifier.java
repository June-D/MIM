package com.junesoon.mim.service;

/**
 * 校验当前登录口令。用于「换身份公钥」等敏感操作，证明操作者知道口令，
 * 而不仅仅是持有一条可能被复制的 JWT。
 */
public interface PasswordVerifier {

    /**
     * @param passwordBase64 原始口令的 Base64，与登录/注册请求的格式一致
     */
    boolean matches(String username, String passwordBase64);
}
