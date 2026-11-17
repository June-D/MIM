package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.DeviceService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备管理。
 *
 * <p>多设备前提：每个设备独立身份密钥与棘轮状态。
 * 客户端首次登录必须先注册设备，才能上传该设备的公钥与预密钥。
 */
@Slf4j
@RestController
@RequestMapping("device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 设备注册请求。
     */
    @Data
    public static class RegisterRequest {
        private String deviceId;
        private String deviceName;
    }

    /**
     * 注册或刷新本设备。重复调用幂等。
     */
    @PostMapping
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Device> register(@CurrentUser String username,
        @CurrentDevice String currentDevice, @RequestBody RegisterRequest request) {
        // 绑定了设备的令牌只能登记自己这台设备。否则盗号者拿走一个有效 JWT，
        // 就能悄悄给自己的设备登记公钥/预密钥，随后收到所有新消息。
        // 新增设备必须在新设备上单独登录，拿到绑定到新设备的令牌。
        if (request != null && request.getDeviceId() != null
            && !request.getDeviceId().trim().isEmpty()
            && currentDevice != null
            && !request.getDeviceId().trim().equals(currentDevice)) {
            throw new UserInvalidException("当前登录令牌只能登记绑定的设备，请在新设备上重新登录");
        }
        Device device = deviceService.register(username, request.getDeviceId(),
            request.getDeviceName());
        return new ObjectRestResponse<Device>().data(device).rel(true);
    }

    /**
     * 列出当前用户的全部有效设备，用于「设备管理」界面。
     */
    @GetMapping
    public ObjectRestResponse<Map<String, Object>> list(@CurrentUser String username) {
        List<Device> devices = deviceService.listActiveDevices(username);
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("count", devices.size());
        body.put("devices", devices);
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /**
     * 注销指定设备。注销后该设备不再接收消息。
     */
    @DeleteMapping("/{deviceId}")
    @RateLimit(limit = 20, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> revoke(@CurrentUser String username,
        @PathVariable String deviceId) {
        deviceService.revoke(username, deviceId);
        return new ObjectRestResponse<Boolean>().data(true).rel(true);
    }
}
