package com.junesoon.mim.exception;

import com.junesoon.mim.constant.CommonConstants;

public class UserTokenException extends BaseException {
    public UserTokenException(String message) {
        super(message, CommonConstants.EX_USER_INVALID_CODE);
    }
}
