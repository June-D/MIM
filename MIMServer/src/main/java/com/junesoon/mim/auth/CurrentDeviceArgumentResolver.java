package com.junesoon.mim.auth;

import com.junesoon.mim.entity.UserKey;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentDevice}：从 {@code X-Device-Id} 头取设备标识。
 *
 * <p>缺失时返回 {@code default}，让尚未升级到多设备的客户端继续可用
 * （它们的所有数据都落在 default 设备下）。
 */
@Component
public class CurrentDeviceArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String DEVICE_HEADER = "X-Device-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentDevice.class)
            && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(DEVICE_HEADER);
        if (!StringUtils.hasText(header)) {
            return UserKey.DEFAULT_DEVICE_ID;
        }
        return header.trim();
    }
}
