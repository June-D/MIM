package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.BlindSignRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.responses.SenderTokenKey;
import com.junesoon.mim.service.SenderTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发送者令牌（Phase 2c）。
 *
 * <p>签发必须鉴权且按账号配额；公钥不鉴权（客户端要先取模数才能致盲）。
 * 整个功能由 {@code MIM_SENDER_TOKEN_ENABLED} 控制，默认关闭。
 *
 * [INPUT]: 依赖 SenderTokenService 与鉴权注解
 * [OUTPUT]: GET /token/key、POST /token/blind
 * [POS]: 匿名提交（/message/anonymous）的凭证来源
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
@RestController
@RequestMapping("token")
public class TokenController {

    private final SenderTokenService senderTokenService;

    public TokenController(SenderTokenService senderTokenService) {
        this.senderTokenService = senderTokenService;
    }

    /**
     * 取盲签名公钥。
     */
    @GetMapping("/key")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<SenderTokenKey> key() {
        return new ObjectRestResponse<SenderTokenKey>().data(senderTokenService.key()).rel(true);
    }

    /**
     * 对致盲值签名。服务端只看到致盲后的值，无法得知 nonce。
     */
    @PostMapping("/blind")
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<String> blind(@CurrentUser String username,
        @RequestBody BlindSignRequest request) {
        String signature = senderTokenService.signBlinded(username,
            request == null ? null : request.getBlinded());
        return new ObjectRestResponse<String>().data(signature).rel(true);
    }
}
