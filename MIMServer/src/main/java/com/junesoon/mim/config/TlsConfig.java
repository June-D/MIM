package com.junesoon.mim.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 按配置动态开启 HTTPS。
 *
 * <p>没有直接写 {@code server.ssl.*}，是因为需要在 keystore 缺失时
 * 优雅降级为 HTTP 并明确告警，而不是让整个应用启动失败。
 *
 * <p>注意：一旦开启 HTTPS，客户端必须把 base URL 改成 {@code https://}，
 * WebSocket 也要从 {@code ws://} 改成 {@code wss://}，否则浏览器/客户端会拒绝混合内容。
 */
@Slf4j
@Configuration
public class TlsConfig {

    /**
     * 由 {@link TlsProperties} 决定是否追加一个 SSL 连接器。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> sslConnectorCustomizer(
        TlsProperties tlsProperties) {
        return factory -> {
            if (!tlsProperties.isEnabled()) {
                log.info("TLS 未启用，服务端以 HTTP 运行（仅限本地开发）");
                return;
            }
            if (!StringUtils.hasText(tlsProperties.getKeystore())) {
                log.error("已要求启用 TLS 但未配置 mim.tls.keystore，将回退为 HTTP。"
                    + "生产环境必须提供证书，否则 JWT 与口令会在网络上明文传输");
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(tlsProperties.getPort());
            connector.setSecure(true);
            connector.setScheme("https");
            connector.setProperty("SSLEnabled", "true");
            connector.setProperty("keystoreFile", tlsProperties.getKeystore());
            connector.setProperty("keystorePass", tlsProperties.getKeystorePassword());
            connector.setProperty("keystoreType", tlsProperties.getKeystoreType());
            connector.setProperty("keyAlias", tlsProperties.getKeyAlias());
            connector.setProperty("clientAuth", String.valueOf(tlsProperties.isClientAuth()));
            // sslEnabledProtocols 接受逗号分隔的列表；sslProtocol 只接受单值，混用会导致
            // "TLSv1.2,TLSv1.3 SSLContext not available"
            connector.setProperty("sslEnabledProtocols",
                String.join(",", tlsProperties.getProtocols()));
            factory.addAdditionalTomcatConnectors(connector);
            log.info("TLS 已启用：https://0.0.0.0:{}（keystore={}）",
                tlsProperties.getPort(), tlsProperties.getKeystore());
        };
    }
}
