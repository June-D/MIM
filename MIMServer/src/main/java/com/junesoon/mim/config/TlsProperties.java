package com.junesoon.mim.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 传输层安全配置。
 *
 * <p>端到端加密保护的是「消息内容」，但口令、JWT、元数据（谁在和谁通信、何时通信）
 * 仍然暴露在传输层。没有 TLS 的话，中间人可以直接窃取 JWT 冒充身份，
 * E2EE 也就形同虚设。因此生产环境必须开启。
 *
 * <p>默认关闭是为了让本地开发不用折腾证书；一旦 {@code mim.tls.enabled=true}
 * 且提供了 keystore，服务端会以 HTTPS 启动，WebSocket 也随之变为 WSS。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mim.tls")
public class TlsProperties {

    /**
     * 是否启用 HTTPS/WSS。
     */
    private boolean enabled = false;

    /**
     * HTTPS 监听端口。
     *
     * <p>必须可配置：写死端口会让测试（随机端口）与同机多实例冲突。
     */
    private int port = 8443;

    /**
     * PKCS12 keystore 路径。
     */
    private String keystore = "";

    /**
     * keystore 口令。
     */
    private String keystorePassword = "";

    /**
     * keystore 类型，通常 PKCS12。
     */
    private String keystoreType = "PKCS12";

    /**
     * 证书别名。
     */
    private String keyAlias = "mim";

    /**
     * 是否要求客户端证书（双向 TLS）。内网高安全场景可开启，
     * 但会让终端用户部署变复杂，默认关闭。
     */
    private boolean clientAuth = false;

    /**
     * 启用的 TLS 协议版本。TLS 1.0/1.1 已被主流浏览器与规范弃用。
     */
    private String[] protocols = {"TLSv1.2", "TLSv1.3"};
}
