package com.junesoon.mim.ratelimit;

import com.junesoon.mim.exception.BaseException;
import com.junesoon.mim.constant.CommonConstants;

/**
 * 触发限流时抛出。返回 429 语义的业务错误码。
 */
public class RateLimitExceededException extends BaseException {

    private static final long serialVersionUID = 1L;

    public RateLimitExceededException(String message) {
        super(message, CommonConstants.EX_RATE_LIMIT_CODE);
    }
}
