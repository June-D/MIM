package com.junesoon.mim.crypto;

/**
 * 加密层异常。区分于网络异常，便于 UI 给出准确提示。
 */
public class IdentityException extends Exception {

    private static final long serialVersionUID = 1L;

    public IdentityException(String message) {
        super(message);
    }

    public IdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
