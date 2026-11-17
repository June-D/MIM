package com.junesoon.mim.auth;

import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.exception.UserTokenException;
import com.junesoon.mim.jwt.JWTInfo;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentDevice}：优先使用 JWT 绑定的设备，其次才看请求头。
 *
 * <p>令牌绑定设备后，请求头不再是身份来源——否则盗号者只要换一个
 * {@code X-Device-Id} 就能以受害者其它设备的身份操作。旧版未绑定设备的令牌
 * 仍回落读请求头，作为升级兼容期。
 */
@Component
public class CurrentDeviceArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String DEVICE_HEADER = "X-Device-Id";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEVICE_PATTERN = "^[A-Za-z0-9._-]{1,64}$";

    private final TokenService tokenService;

    public CurrentDeviceArgumentResolver(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentDevice.class)
            && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        // 同一请求里 @CurrentUser 通常已经解析过令牌，直接复用，避免重复查设备表
        Object cached = webRequest.getAttribute(TokenService.REQUEST_ATTRIBUTE,
            RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof JWTInfo) {
            String deviceId = ((JWTInfo) cached).getDeviceId();
            return StringUtils.hasText(deviceId) ? deviceId : headerDevice(webRequest);
        }

        String token = extractToken(webRequest.getHeader(AUTH_HEADER));
        if (StringUtils.hasText(token)) {
            try {
                JWTInfo info = tokenService.resolve(token);
                webRequest.setAttribute(TokenService.REQUEST_ATTRIBUTE, info,
                    RequestAttributes.SCOPE_REQUEST);
                if (StringUtils.hasText(info.getDeviceId())) {
                    return info.getDeviceId();
                }
            } catch (UserTokenException e) {
                throw e;
            } catch (Exception e) {
                // 凭证错误交给 @CurrentUser 统一报 401，这里不重复抛
            }
        }
        return headerDevice(webRequest);
    }

    private String headerDevice(NativeWebRequest webRequest) {
        String header = webRequest.getHeader(DEVICE_HEADER);
        if (!StringUtils.hasText(header)) {
            return UserKey.DEFAULT_DEVICE_ID;
        }
        String device = header.trim();
        // 服务端后续会把它写进消息、公钥与文件名，必须先限制字符集和长度。
        if (!device.matches(DEVICE_PATTERN)) {
            throw new UserInvalidException("设备标识只能包含字母、数字、点、下划线、短横线，最长 64 位");
        }
        return device;
    }

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
