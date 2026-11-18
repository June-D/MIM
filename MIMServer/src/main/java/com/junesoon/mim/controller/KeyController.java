package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.KeyUploadRequest;
import com.junesoon.mim.responses.KeyBundle;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.KeyService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备端到端加密公钥目录。
 *
 * <p>公钥是公开信息，查询不需要鉴权（任何想给某人发消息的客户端都要先拿到对方公钥）；
 * 上传必须鉴权，且只能覆盖<b>自己已注册设备</b>的公钥。
 */
@Slf4j
@RestController
@RequestMapping("key")
public class KeyController {

    private final KeyService keyService;
    private final UserKeyMapper userKeyMapper;

    public KeyController(KeyService keyService, UserKeyMapper userKeyMapper) {
        this.keyService = keyService;
        this.userKeyMapper = userKeyMapper;
    }

    /**
     * 上传/更新本设备的加密公钥。同一把公钥重复上传幂等，换钥则版本自增。
     */
    @PostMapping
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<KeyBundle> upload(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody KeyUploadRequest request) {
        KeyBundle bundle = keyService.upload(username, deviceId, request.getPublicKey(),
            request.getAlgorithm(), request.getPassword(), request.getSupportsPadding());
        log.info("用户 {} 设备 {} 上传加密公钥，版本 {} 指纹 {}",
            username, deviceId, bundle.getVersion(), bundle.getFingerprint());
        return new ObjectRestResponse<KeyBundle>().data(bundle).rel(true);
    }

    /**
     * 查询指定用户的公钥。
     *
     * <p>不传 deviceId 时返回<b>该用户所有有效设备</b>的公钥列表 ——
     * 多设备下发消息必须为每个设备各加密一份。
     */
    @GetMapping("/{username}")
    @RateLimit(limit = 300, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<List<KeyBundle>> get(@PathVariable String username,
        @RequestParam(value = "deviceId", required = false) String deviceId) {
        ValidationUtil.validateUsername(username);
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            return new ObjectRestResponse<List<KeyBundle>>()
                .data(java.util.Collections.singletonList(keyService.get(username, deviceId)))
                .rel(true);
        }
        return new ObjectRestResponse<List<KeyBundle>>()
            .data(keyService.getAllDevices(username))
            .rel(true);
    }

    /**
     * 查询本设备的公钥状态，客户端启动时用来判断是否需要重新上传。
     */
    @GetMapping("/self")
    public ObjectRestResponse<KeyBundle> self(@CurrentUser String username,
        @CurrentDevice String deviceId) {
        UserKey key = userKeyMapper.selectByDevice(username, deviceId);
        if (key == null) {
            return new ObjectRestResponse<KeyBundle>().rel(false);
        }
        KeyBundle bundle = new KeyBundle();
        bundle.setUsername(key.getUsername());
        bundle.setDeviceId(key.getDeviceId());
        bundle.setPublicKey(key.getPublicKey());
        bundle.setAlgorithm(key.getAlgorithm());
        bundle.setVersion(key.getVersion());
        bundle.setFingerprint(key.getFingerprint());
        bundle.setSupportsPadding(Boolean.TRUE.equals(key.getSupportsPadding()));
        return new ObjectRestResponse<KeyBundle>().data(bundle).rel(true);
    }
}
