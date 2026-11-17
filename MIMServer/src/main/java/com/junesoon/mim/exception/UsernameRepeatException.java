package com.junesoon.mim.exception;

import com.junesoon.mim.constant.CommonConstants;

public class UsernameRepeatException extends BaseException {
    public UsernameRepeatException(String message) {
        super(message, CommonConstants.EX_USERNAME_REPEAT_INVALID_CODE);
    }
}
