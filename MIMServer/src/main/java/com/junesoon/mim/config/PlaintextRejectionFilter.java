package com.junesoon.mim.config;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 启用 TLS 后拒绝明文 HTTP 上的业务请求。
 *
 * <p>Tomcat 在开启 HTTPS 的同时仍会在 {@code server.port}（默认 8080）上监听 HTTP。
 * 如果不在应用层拒绝，攻击者可以直接访问明文端口嗅探登录口令与 JWT，
 * 再走 HTTPS 冒充用户——传输层加密就被完全绕过了。
 *
 * <p>该过滤器只在 {@code mim.tls.enabled=true} 且
 * {@code mim.tls.block-plaintext=true}（默认）时生效。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PlaintextRejectionFilter extends OncePerRequestFilter {

    private final TlsProperties tlsProperties;

    public PlaintextRejectionFilter(TlsProperties tlsProperties) {
        this.tlsProperties = tlsProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        if (tlsProperties.isEnabled() && tlsProperties.isBlockPlaintext()
            && "http".equalsIgnoreCase(request.getScheme())) {
            response.setStatus(403);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"status\":403,\"rel\":false,\"message\":\"请使用 HTTPS 访问\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
