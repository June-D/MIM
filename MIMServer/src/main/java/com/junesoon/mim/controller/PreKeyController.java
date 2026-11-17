package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.PreKeyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预密钥包接口（X3DH 密钥分发）。
 *
 * <p>拉取对方的包需要鉴权（避免匿名枚举用户关系），但不需要对方在线 ——
 * 这正是解决「无法对离线用户发起会话」的关键。
 *
 * <p>多设备下 {@code GET /prekey/{username}} 返回<b>该用户每个有效设备各一个包</b>，
 * 发起方需要为每个设备分别建立会话。
 */
@Slf4j
@RestController
@RequestMapping("prekey")
public class PreKeyController {

    private final PreKeyService preKeyService;

    public PreKeyController(PreKeyService preKeyService) {
        this.preKeyService = preKeyService;
    }

    /**
     * 上传/轮换本设备的签名预密钥。
     */
    @PostMapping("/signed")
    @RateLimit(limit = 20, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<SignedPreKey> uploadSigned(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody SignedPreKey request) {
        return new ObjectRestResponse<SignedPreKey>()
            .data(preKeyService.uploadSignedPreKey(username, deviceId, request))
            .rel(true);
    }

    /**
     * 批量上传本设备的一次性预密钥。
     */
    @PostMapping("/one-time")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Integer> uploadOneTime(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody List<OneTimePreKey> keys) {
        return new ObjectRestResponse<Integer>()
            .data(preKeyService.uploadOneTimePreKeys(username, deviceId, keys))
            .rel(true);
    }

    /**
     * 查询本设备剩余的一次性预密钥数量，客户端据此决定是否补充。
     */
    @GetMapping("/status")
    public ObjectRestResponse<Map<String, Object>> status(@CurrentUser String username,
        @CurrentDevice String deviceId) {
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("deviceId", deviceId);
        body.put("oneTimePreKeys", preKeyService.countOneTimePreKeys(username, deviceId));
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /**
     * 拉取对方<b>所有有效设备</b>的预密钥包。每个包会消耗对应设备一个一次性预密钥。
     */
    @GetMapping("/{username}")
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<List<PreKeyService.PreKeyBundle>> fetch(
        @CurrentUser String self, @PathVariable String username) {
        return new ObjectRestResponse<List<PreKeyService.PreKeyBundle>>()
            .data(preKeyService.fetchBundles(username))
            .rel(true);
    }
}
