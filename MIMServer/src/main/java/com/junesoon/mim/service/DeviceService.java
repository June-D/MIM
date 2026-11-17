package com.junesoon.mim.service;

import com.junesoon.mim.entity.Device;
import java.util.List;

/**
 * 设备注册与查询。
 */
public interface DeviceService {

    /**
     * 登记设备。重复登记幂等，会刷新 last_seen。
     *
     * @param deviceName 仅供用户辨认，可为空
     */
    Device register(String username, String deviceId, String deviceName);

    List<Device> listActiveDevices(String username);

    /**
     * 注销设备。注销后不再接收消息，也不再出现在预密钥分发中。
     */
    void revoke(String username, String deviceId);

    /**
     * 使该设备已签发的 JWT 全部失效（登出时调用）。
     */
    void invalidateTokens(String username, String deviceId);

    /**
     * 刷新活跃时间，用于设备列表排序与「陈旧设备」识别。
     */
    void touch(String username, String deviceId);
}
