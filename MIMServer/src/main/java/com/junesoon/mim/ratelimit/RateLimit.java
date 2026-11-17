package com.junesoon.mim.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级限流。标注在 Controller 方法上。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 同一主体在该窗口内允许的最大请求数。
     */
    int limit() default 30;

    /**
     * 统计窗口长度（秒）。
     */
    int windowSeconds() default 60;

    /**
     * 限流维度。
     */
    Dimension dimension() default Dimension.USER;

    /**
     * 限流维度。
     */
    enum Dimension {
        /**
         * 按登录用户名。未登录请求退化到按 IP。
         */
        USER,
        /**
         * 按客户端 IP。用于登录/注册这类还没有身份的接口。
         */
        IP,
        /**
         * 用户名 + IP 双重限制，取更严格者。
         */
        BOTH
    }
}
