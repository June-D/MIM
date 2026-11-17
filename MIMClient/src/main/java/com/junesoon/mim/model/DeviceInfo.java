package com.junesoon.mim.model;

import lombok.Data;

/**
 * 客户端视角的设备信息。
 */
@Data
public class DeviceInfo {

    private String username;

    private String deviceId;

    private String deviceName;

    private Long crtTime;

    private Long lastSeen;

    private Boolean revoked;
}
