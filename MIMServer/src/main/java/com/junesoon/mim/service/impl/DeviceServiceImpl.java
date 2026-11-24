package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.service.DeviceService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    public DeviceServiceImpl(DeviceMapper deviceMapper, JdbcTemplate jdbcTemplate) {
        this.deviceMapper = deviceMapper;
        this.jdbcTemplate = jdbcTemplate;
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
        // 新设备从 0 开始；已有设备保留当前版本（upsert 不会覆盖 token_version）
        device.setTokenVersion(existing == null || existing.getTokenVersion() == null
            ? 0 : existing.getTokenVersion());
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
    public void invalidateTokens(String username, String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            return;
        }
        ValidationUtil.validateUsername(username);
        int affected = deviceMapper.bumpTokenVersion(username, deviceId.trim());
        if (affected == 0) {
            log.debug("设备 {} 不存在，无需失效令牌", deviceId);
        }
    }

    @Override
    public void updatePushRegistration(String username, String deviceId, String registrationId) {
        // registrationId 由极光下发，长度有限；超长直接拒，避免脏数据写进库里。
        String value = registrationId == null ? "" : registrationId.trim();
        if (value.length() > 64) {
            throw new UserInvalidException("registrationId 不合法");
        }
        // 走 JdbcTemplate 而不是加 Mapper 方法：这条语句只影响一列，
        // 为它改动 DeviceMapper 接口会让所有测试桩都得跟着实现一遍。
        jdbcTemplate.update(
            "update t_device set push_registration = ? where username = ? and device_id = ?",
            value, username, deviceId);
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
