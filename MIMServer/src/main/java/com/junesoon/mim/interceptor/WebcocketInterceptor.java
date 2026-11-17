package com.junesoon.mim.interceptor;

import com.junesoon.mim.auth.TokenService;
import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.jwt.JWTInfo;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手鉴权。
 *
 * <p>握手时校验 JWT 并取出设备标识，都放进 session attributes。
 * 业务层一律用这里的用户名，绝不信任客户端报文里自称的身份。
 *
 * <p>设备标识来自 {@code X-Device-Id} 头；缺失时降级为 default，
 * 让尚未升级到多设备的客户端仍能工作。
 */
@Slf4j
@Component
public class WebcocketInterceptor implements HandshakeInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String DEVICE_HEADER = "X-Device-Id";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public WebcocketInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
        WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getHeaders().getFirst(AUTH_HEADER));
        if (!StringUtils.hasText(token)) {
            log.debug("WebSocket 握手被拒绝：缺少 Authorization 头");
            return false;
        }
        try {
            JWTInfo info = tokenService.resolve(token);
            if (info == null || !StringUtils.hasText(info.getUsername())) {
                return false;
            }
            attributes.put(CommonConstants.WEB_SOCKET_USER_ID, info.getUsername());
            // 令牌绑定的设备优先；旧版未绑定设备时才看握手头
            attributes.put(CommonConstants.WEB_SOCKET_DEVICE_ID,
                StringUtils.hasText(info.getDeviceId())
                    ? info.getDeviceId()
                    : normalizeDevice(request.getHeaders().getFirst(DEVICE_HEADER)));
            return true;
        } catch (Exception e) {
            log.debug("WebSocket 握手被拒绝：凭证无效 {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
        WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private String normalizeDevice(String header) {
        if (!StringUtils.hasText(header)) {
            return UserKey.DEFAULT_DEVICE_ID;
        }
        String value = header.trim();
        // 设备标识只允许安全字符，避免被用作注入其它语义
        return value.matches("^[A-Za-z0-9._-]{1,64}$") ? value : UserKey.DEFAULT_DEVICE_ID;
    }

    private String extractToken(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }
}
