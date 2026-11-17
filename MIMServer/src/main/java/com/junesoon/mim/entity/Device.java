package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户设备。
 *
 * <p>多设备的核心：每个设备有独立的身份密钥与棘轮状态。
 * 给用户发消息时要为他的每个设备各加密一份。
 */
@Data
public class Device implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    /**
     * 客户端生成的设备标识。使用随机串而非自增，避免被猜测。
     */
    private String deviceId;

    /**
     * 设备名，仅用于用户在「设备列表」里辨认，不参与加密。
     */
    private String deviceName;

    private Long crtTime;

    private Long lastSeen;

    /**
     * 是否已注销。注销后不再接收新消息，也不再出现在预密钥分发里。
     */
    private Boolean revoked;

    /**
     * 设备令牌版本。登出或注销时自增，所有旧 JWT 立即失效。
     */
    private Integer tokenVersion;
}
