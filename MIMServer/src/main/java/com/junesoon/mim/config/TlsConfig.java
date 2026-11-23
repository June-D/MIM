package com.junesoon.mim.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
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
     *
     * <p>必须走 {@link SSLHostConfig} 而不是给 Connector 塞 keystore 属性：
     * 只用 {@code connector.setProperty("keystoreFile", ...)} 时 Tomcat 10
     * 找不到 {@code _default_} 的 SSLHostConfig，启动直接抛
     * {@code IllegalArgumentException: No SSLHostConfig element was found}。
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
            // 这一行决定端点是否真的走 SSL：只加 SSLHostConfig 而不设它，
            // 8443 会变成「明文 HTTP 端口」，客户端握手直接被当成非法请求头。
            connector.setProperty("SSLEnabled", "true");

            SSLHostConfig hostConfig = new SSLHostConfig();
            hostConfig.setHostName("_default_");
            // 只放开配置里声明的协议：TLS 1.0/1.1 已在规范里废弃。
            hostConfig.setProtocols(String.join(",", tlsProperties.getProtocols()));
            if (tlsProperties.isClientAuth()) {
                hostConfig.setCertificateVerification("required");
            }

            SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                hostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
            certificate.setCertificateKeystoreFile(tlsProperties.getKeystore());
            certificate.setCertificateKeystorePassword(tlsProperties.getKeystorePassword());
            certificate.setCertificateKeystoreType(tlsProperties.getKeystoreType());
            certificate.setCertificateKeyAlias(tlsProperties.getKeyAlias());
            hostConfig.addCertificate(certificate);

            connector.addSslHostConfig(hostConfig);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("TLS 已启用：https://0.0.0.0:{}（keystore={}，协议={}）",
                tlsProperties.getPort(), tlsProperties.getKeystore(),
                String.join("/", tlsProperties.getProtocols()));
        };
    }
}
