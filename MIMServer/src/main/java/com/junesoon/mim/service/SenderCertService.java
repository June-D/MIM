package com.junesoon.mim.service;

import com.junesoon.mim.responses.SenderCertKey;
import com.junesoon.mim.responses.SenderCertificate;

/**
 * 发送者证书签发（Phase 2a）。
 *
 * <p>证书把「用户名 + 设备 + 身份公钥」用服务端独立 RSA 密钥签住。
 * 它只回答「这条消息是谁发的」，不提供提交匿名；匿名提交属于 2c，
 * 需要单独的防重放与配额体系。
 *
 * <p>功能由 {@code mim.sender-cert.enabled} 控制，默认关闭。
 */
public interface SenderCertService {

    /**
     * 是否启用。关闭时所有接口返回「未启用」，不生成密钥。
     */
    boolean enabled();

    /**
     * 验签公钥。首次调用时才加载/生成密钥对。
     */
    SenderCertKey key();

    /**
     * 为当前身份签发一张证书；用户名取自鉴权身份，设备必须已上传身份公钥。
     */
    SenderCertificate issue(String username, String deviceId);
}
