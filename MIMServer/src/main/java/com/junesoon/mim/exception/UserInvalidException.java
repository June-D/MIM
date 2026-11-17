package com.junesoon.mim.exception;

import com.junesoon.mim.constant.CommonConstants;

public class UserInvalidException extends BaseException {
    public UserInvalidException(String message) {
        super(message, CommonConstants.EX_USER_PASS_INVALID_CODE);
    }

    public UserInvalidException(String message, int status) {
        super(message, status);
    }
}
