package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.responses.SenderCertKey;
import com.junesoon.mim.responses.SenderCertificate;
import com.junesoon.mim.service.SenderCertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发送者证书（Phase 2a）。
 *
 * <p>证书让收信端在没有 {@code from_user} 的情况下确认发送者身份。
 * 公钥接口不鉴权（客户端要在登录前就能 pin），签发必须鉴权，
 * 且用户名只取 JWT，请求体无法影响归属。
 *
 * <p>整个功能由 {@code MIM_SENDER_CERT_ENABLED} 控制，默认关闭。
 *
 * [INPUT]: 依赖 SenderCertService 与鉴权注解
 * [OUTPUT]: GET /cert/key、POST /cert/sender
 * [POS]: 消息路径之外的独立信任材料；2b 才会把它接进首条消息
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
@RestController
@RequestMapping("cert")
public class CertController {

    private final SenderCertService senderCertService;

    public CertController(SenderCertService senderCertService) {
        this.senderCertService = senderCertService;
    }

    /**
     * 取证书验签公钥。客户端首次拿到后应当 pin 住，不能每次都无条件采信。
     */
    @GetMapping("/key")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<SenderCertKey> key() {
        return new ObjectRestResponse<SenderCertKey>().data(senderCertService.key()).rel(true);
    }

    /**
     * 为本设备签发一张证书。
     */
    @PostMapping("/sender")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<SenderCertificate> sender(@CurrentUser String username,
        @CurrentDevice String deviceId) {
        SenderCertificate certificate = senderCertService.issue(username, deviceId);
        log.info("用户 {} 设备 {} 签发发送者证书", username, deviceId);
        return new ObjectRestResponse<SenderCertificate>().data(certificate).rel(true);
    }
}
