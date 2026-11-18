package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.SenderCertKeyView;
import com.junesoon.mim.model.SenderCertificateView;
import feign.RequestLine;

/**
 * 发送者证书接口（Phase 2b）。
 *
 * <p>公钥接口用于首次固定信任锚，签发接口按当前 JWT 身份返回证书。
 * 两者都受服务端开关控制；关闭时返回 40001。
 *
 * [INPUT]: 依赖服务端的 /cert/key 与 /cert/sender
 * [OUTPUT]: 提供 key() 与 sender()
 * [POS]: SenderCertService 的网络层；不参与消息加解密
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public interface CertFeign extends FeignAPI {

    @RequestLine("GET /cert/key")
    ObjectRestResponse<SenderCertKeyView> key();

    @RequestLine("POST /cert/sender")
    ObjectRestResponse<SenderCertificateView> sender();
}
