package com.junesoon.mim.auth;

import com.junesoon.mim.exception.UserTokenException;
import com.junesoon.mim.utils.UserAuthUtil;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 Authorization 头里的 JWT 解析成用户名，注入到 {@link CurrentUser} 参数。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserAuthUtil userAuthUtil;

    public CurrentUserArgumentResolver(UserAuthUtil userAuthUtil) {
        this.userAuthUtil = userAuthUtil;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
            && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String token = extractToken(webRequest.getHeader(AUTH_HEADER));
        if (!StringUtils.hasText(token)) {
            throw new UserTokenException("缺少登录凭证");
        }
        try {
            String username = userAuthUtil.getUsername(token);
            if (!StringUtils.hasText(username)) {
                throw new UserTokenException("登录凭证无效");
            }
            return username;
        } catch (UserTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new UserTokenException("登录凭证无效");
        }
    }

    /**
     * 兼容 {@code Authorization: <token>} 与 {@code Authorization: Bearer <token>} 两种写法。
     */
    private String extractToken(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }
}
