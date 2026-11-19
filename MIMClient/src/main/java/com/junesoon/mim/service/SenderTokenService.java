package com.junesoon.mim.service;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.BlindRsaToken;
import com.junesoon.mim.feign.BlindSignRequest;
import com.junesoon.mim.feign.TokenFeign;
import com.junesoon.mim.model.SenderTokenKeyView;
import lombok.extern.slf4j.Slf4j;

/**
 * 发送者令牌的获取（Phase 2c）。
 *
 * <p>令牌一次性使用，不做缓存；服务端未启用或网络失败时返回 null，
 * 由调用方决定降级（当前只在匿名提交路径使用）。
 *
 * [INPUT]: 依赖 TokenFeign 与 BlindRsaToken
 * [OUTPUT]: issueToken()
 * [POS]: /message/anonymous 的凭证来源
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
public final class SenderTokenService {

    private static final SenderTokenService INSTANCE = new SenderTokenService();

    public static SenderTokenService getInstance() {
        return INSTANCE;
    }

    /**
     * 申请一个一次性令牌；失败返回 null。
     */
    public String issueToken() {
        try {
            ObjectRestResponse<SenderTokenKeyView> keyResponse =
                Request.connector(TokenFeign.class).key();
            if (keyResponse == null || keyResponse.getStatus() != 200
                || keyResponse.getData() == null
                || keyResponse.getData().getPublicKey() == null) {
                log.debug("发送者令牌不可用：{}",
                    keyResponse == null ? "无响应" : keyResponse.getMessage());
                return null;
            }
            String publicKey = keyResponse.getData().getPublicKey();
            byte[] nonce = BlindRsaToken.newNonce();
            BlindRsaToken.Blinded blinded = BlindRsaToken.blind(nonce, publicKey);

            ObjectRestResponse<String> signResponse = Request.connector(TokenFeign.class)
                .blind(new BlindSignRequest(blinded.getBlindedValue()));
            if (signResponse == null || signResponse.getStatus() != 200
                || signResponse.getData() == null) {
                log.debug("致盲签名失败：{}",
                    signResponse == null ? "无响应" : signResponse.getMessage());
                return null;
            }
            String token = BlindRsaToken.unblind(blinded, signResponse.getData());
            if (!BlindRsaToken.verify(token, publicKey)) {
                log.warn("服务端返回的盲签名无法验证，拒绝使用该令牌");
                return null;
            }
            return token;
        } catch (Exception e) {
            log.debug("申请发送者令牌失败：{}", e.getMessage());
            return null;
        }
    }
}
