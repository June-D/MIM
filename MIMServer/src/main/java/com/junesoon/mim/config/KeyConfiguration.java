package com.junesoon.mim.config;

import lombok.Data;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 签名密钥。密钥对在首次启动时生成并持久化到 t_rsa_key，
 * 不在配置文件中保存任何固定密钥。
 */
@Configuration
@Data
public class KeyConfiguration {

    private byte[] userPubKey;

    private byte[] userPriKey;
}
