package com.junesoon.mim.feign.codec;

import com.junesoon.mim.components.ApplicatonStore;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 统一注入登录凭证与设备标识。
 *
 * <p>所有业务请求都走 Header：
 * <ul>
 *   <li>{@code Authorization}：JWT，不再拼进 URL；</li>
 *   <li>{@code X-Device-Id}：设备标识。多设备下密钥按设备划分，
 *       服务端需要它来定位当前设备的公钥与离线消息。</li>
 * </ul>
 */
public class AppRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String token = ApplicatonStore.getToken();
        if (token != null && !token.trim().isEmpty()) {
            template.header("Authorization", "Bearer " + token);
        }
        String deviceId = ApplicatonStore.getDeviceId();
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            template.header("X-Device-Id", deviceId);
        }
    }
}
