package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.service.DeviceService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class DeviceServiceImpl implements DeviceService {

    /**
     * 单用户设备数量上限。设备越多，发一条消息需要加密的份数越多，
     * 也越容易被用来放大攻击面，因此设一个明确上限。
     */
    private static final int MAX_DEVICES_PER_USER = 10;

    private static final int MAX_DEVICE_NAME_LENGTH = 64;

    private final DeviceMapper deviceMapper;

    public DeviceServiceImpl(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    @Override
    public Device register(String username, String deviceId, String deviceName) {
        ValidationUtil.validateUsername(username);
        String id = validateDeviceId(deviceId);
        String name = deviceName == null ? null : deviceName.trim();
        if (name != null && name.length() > MAX_DEVICE_NAME_LENGTH) {
            throw new UserInvalidException("设备名过长");
        }

        // 已存在则只是刷新，不算新增
        Device existing = deviceMapper.selectOne(username, id);
        boolean isNew = existing == null || Boolean.TRUE.equals(existing.getRevoked());
        if (isNew && deviceMapper.countActiveDevices(username) >= MAX_DEVICES_PER_USER) {
            throw new UserInvalidException("设备数量已达上限（" + MAX_DEVICES_PER_USER
                + "），请先注销不用的设备");
        }

        long now = System.currentTimeMillis();
        Device device = new Device();
        device.setUsername(username);
        device.setDeviceId(id);
        device.setDeviceName(StringUtils.hasText(name) ? name : defaultName(id));
        device.setCrtTime(existing == null || existing.getCrtTime() == null ? now : existing.getCrtTime());
        device.setLastSeen(now);
        device.setRevoked(false);
        deviceMapper.upsert(device);
        if (isNew) {
            log.info("用户 {} 注册新设备 {}", username, id);
        }
        return device;
    }

    @Override
    public List<Device> listActiveDevices(String username) {
        ValidationUtil.validateUsername(username);
        List<Device> devices = deviceMapper.selectActiveDevices(username);
        return devices == null ? Collections.emptyList() : devices;
    }

    @Override
    public void revoke(String username, String deviceId) {
        ValidationUtil.validateUsername(username);
        String id = validateDeviceId(deviceId);
        int affected = deviceMapper.revoke(username, id);
        if (affected == 0) {
            throw new UserInvalidException("设备不存在");
        }
        log.info("用户 {} 注销设备 {}", username, id);
    }

    @Override
    public void touch(String username, String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            return;
        }
        try {
            deviceMapper.touch(username, deviceId.trim(), System.currentTimeMillis());
        } catch (Exception e) {
            // 活跃时间只是辅助信息，不影响主流程
            log.debug("刷新设备活跃时间失败：{}", e.getMessage());
        }
    }

    private String validateDeviceId(String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            throw new UserInvalidException("设备标识不能为空");
        }
        String id = deviceId.trim();
        if (!id.matches("^[A-Za-z0-9._-]{1,64}$")) {
            throw new UserInvalidException("设备标识只能包含字母、数字、点、下划线、短横线，最长 64 位");
        }
        return id;
    }

    /**
     * 设备名缺省值。用标识前缀，便于用户在列表里区分。
     */
    private String defaultName(String deviceId) {
        return "设备 " + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
    }
}
