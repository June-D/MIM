package com.junesoon.mim.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 从请求头 {@code X-Device-Id} 注入当前设备标识。
 *
 * <p>多设备下几乎所有密钥相关操作都需要设备维度：上传公钥、发布预密钥、
 * 拉取自己的离线消息。放在独立注解里而不是塞进 {@link CurrentUser}，
 * 是因为「没有设备头的旧客户端」应当降级到 default 设备而不是报错。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentDevice {
}
