package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.Device;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DeviceMapper {

    /**
     * 登记或更新设备。重复注册是幂等的（更新 last_seen 与设备名）。
     */
    int upsert(Device device);

    Device selectOne(@Param("username") String username, @Param("deviceId") String deviceId);

    /**
     * 列出某用户所有未注销的设备。
     */
    List<Device> selectActiveDevices(@Param("username") String username);

    int touch(@Param("username") String username, @Param("deviceId") String deviceId,
        @Param("lastSeen") long lastSeen);

    /**
     * 注销设备。用 revoked 标记而不是物理删除，
     * 这样历史消息的 from_device_id 仍可追溯。
     */
    int revoke(@Param("username") String username, @Param("deviceId") String deviceId);

    /**
     * 自增设备令牌版本。该设备已签发的 JWT 会立即失效。
     */
    int bumpTokenVersion(@Param("username") String username,
        @Param("deviceId") String deviceId);

    /**
     * 自增该用户所有设备的令牌版本（改密时调用）。
     */
    int bumpAllTokenVersions(@Param("username") String username);

    int countActiveDevices(@Param("username") String username);
}
