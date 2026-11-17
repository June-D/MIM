package com.junesoon.mim.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 方法参数上，注入当前登录用户的用户名。
 *
 * <p>用户名来自 Authorization 请求头中的 JWT，而不是 URL 路径，
 * 避免 token 落进访问日志、浏览器历史与 Referer。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
