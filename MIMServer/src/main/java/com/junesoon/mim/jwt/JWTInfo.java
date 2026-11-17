package com.junesoon.mim.jwt;

import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * JWT 载荷。
 *
 * <p>多设备下令牌必须绑定到具体设备：
 * <ul>
 *   <li>{@code deviceId}：签发时的设备。服务端以它为准，不再相信请求头；</li>
 *   <li>{@code tokenVersion}：设备令牌版本。登出/注销时自增，旧令牌立即失效；</li>
 *   <li>{@code jti}：单条令牌标识，保留给审计与更细粒度的吊销。</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class JWTInfo implements Serializable {

    private String username;
    private String userId;
    private String nickname;

    /**
     * 绑定的设备。为空表示旧版未绑定设备的令牌（仅用于兼容期）。
     */
    private String deviceId;

    /**
     * 签发时的设备令牌版本。
     */
    private int tokenVersion;

    /**
     * 令牌唯一标识（JWT 的 jti）。
     */
    private String jti;

    /**
     * 签发时间（毫秒）。
     */
    private long issuedAt;

    /**
     * 过期时间（毫秒，来自 JWT 的 exp）。WebSocket 用它判断连接是否应断开。
     */
    private long expiresAt;

    /**
     * 兼容旧调用：未绑定设备、无过期时间的令牌。
     */
    public JWTInfo(String username, String userId, String nickname) {
        this(username, userId, nickname, null, 0, null, 0L, 0L);
    }

    /**
     * 兼容签发调用：过期时间由 JWTHelper 写入，无需调用方提供。
     */
    public JWTInfo(String username, String userId, String nickname, String deviceId,
        int tokenVersion, String jti, long issuedAt) {
        this(username, userId, nickname, deviceId, tokenVersion, jti, issuedAt, 0L);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JWTInfo jwtInfo = (JWTInfo) o;
        if (!Objects.equals(username, jwtInfo.username)) {
            return false;
        }
        return Objects.equals(userId, jwtInfo.userId);
    }

    @Override
    public int hashCode() {
        int result = username != null ? username.hashCode() : 0;
        result = 31 * result + (userId != null ? userId.hashCode() : 0);
        return result;
    }
}
