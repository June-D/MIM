package com.junesoon.mim.exception;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.ratelimit.RateLimitExceededException;
import com.junesoon.mim.responses.BaseResponse;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 全局异常处理。
 *
 * <p>业务错误统一用 HTTP 200 + 业务码返回（前端按 status 判断），
 * 但「限流」和「未认证」需要用标准 HTTP 状态码，方便网关/客户端中间件
 * 直接识别并做退避重试。
 */
@ControllerAdvice("com.junesoon.mim")
@ResponseBody
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 限流：返回 429，并带 Retry-After 头。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public BaseResponse rateLimitHandler(HttpServletResponse response, RateLimitExceededException ex) {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        logger.warn("触发限流：{}", ex.getMessage());
        return new BaseResponse(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(UserTokenException.class)
    public BaseResponse userTokenExceptionHandler(HttpServletResponse response, UserTokenException ex) {
        // 未认证用 401，客户端可据此触发重新登录
        response.setStatus(401);
        logger.warn("凭证无效：{}", ex.getMessage());
        return new BaseResponse(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(UserInvalidException.class)
    public BaseResponse userInvalidExceptionHandler(HttpServletResponse response, UserInvalidException ex) {
        response.setStatus(200);
        logger.warn("请求被拒绝：{}", ex.getMessage());
        return new BaseResponse(ex.getStatus(), ex.getMessage());
    }

    /**
     * 用户名重复是预期内的业务结果。用 HTTP 200 返回，客户端只展示「用户名已存在」。
     */
    @ExceptionHandler(UsernameRepeatException.class)
    public BaseResponse usernameRepeatHandler(HttpServletResponse response, UsernameRepeatException ex) {
        response.setStatus(200);
        logger.info("用户名已存在");
        return new BaseResponse(ex.getStatus(), "用户名已存在");
    }

    @ExceptionHandler(BaseException.class)
    public BaseResponse baseExceptionHandler(HttpServletResponse response, BaseException ex) {
        logger.error(ex.getMessage(), ex);
        response.setStatus(500);
        return new BaseResponse(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse otherExceptionHandler(HttpServletResponse response, Exception ex) {
        response.setStatus(500);
        logger.error(ex.getMessage(), ex);
        // 不把内部异常细节返回给客户端，避免泄漏实现信息
        return new BaseResponse(CommonConstants.EX_OTHER_CODE, "服务器内部错误");
    }
}
