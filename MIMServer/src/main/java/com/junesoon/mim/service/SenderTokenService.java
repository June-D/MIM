package com.junesoon.mim.service;

import com.junesoon.mim.responses.SenderTokenKey;

/**
 * 发送者令牌（Phase 2c）。
 *
 * <p>令牌是「提交者确实是一个真实账号」的盲签名凭证：签发时鉴权，
 * 但服务端看不到 nonce，事后无法把令牌映射回账号。
 *
 * <p>功能由 {@code mim.sender-token.enabled} 控制，默认关闭。
 */
public interface SenderTokenService {

    boolean enabled();

    /**
     * 验签公钥。首次调用时才加载/生成密钥对。
     */
    SenderTokenKey key();

    /**
     * 对客户端致盲后的值签名。{@code username} 只用于配额计数，
     * 不参与任何存储，签完即丢。
     */
    String signBlinded(String username, String blinded);

    /**
     * 校验并消费令牌：签名、时间窗、重放（Redis 一次性）。
     *
     * @return 令牌里的 nonce（Base64），仅用于日志排查，不落库
     */
    String consume(String token);
}
