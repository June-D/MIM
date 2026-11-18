package com.junesoon.mim.service;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.ServerCertPin;
import com.junesoon.mim.feign.CertFeign;
import com.junesoon.mim.model.SenderCertKeyView;
import com.junesoon.mim.model.SenderCertificateView;
import lombok.extern.slf4j.Slf4j;

/**
 * 发送者证书的获取与信任锚固定（Phase 2b）。
 *
 * <p>服务端可能没开这个功能、或还部署着旧版本：所有失败都返回 null，
 * 由调用方降级为旧的明文发送者格式，绝不能让首条消息发不出去。
 *
 * <p>证书按设备签发、24 小时有效，这里在内存里缓存到过期前 1 分钟。
 * 验签公钥第一次拿到后由 {@link ServerCertPin} 固定。
 *
 * [INPUT]: 依赖 CertFeign 与服务端 /cert/* 接口
 * [OUTPUT]: pinnedPublicKey() / certificate()
 * [POS]: MessageService 首条消息发送路径；解密路径只用 pinnedPublicKey()
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
public final class SenderCertService {

    /** 缓存提前量：避免拿着快过期的证书刚好在验签时过期。 */
    private static final long CACHE_MARGIN_MS = 60L * 1000;

    private static final SenderCertService INSTANCE =
        new SenderCertService(new ServerCertPin());

    public static SenderCertService getInstance() {
        return INSTANCE;
    }

    private final ServerCertPin pin;

    private volatile SenderCertificateView cached;

    private volatile long cachedExpiresAt;

    public SenderCertService(ServerCertPin pin) {
        this.pin = pin;
    }

    /**
     * 验签公钥（固定值）。未固定时先拉取并固定；失败返回 null。
     */
    public String pinnedPublicKey() {
        ServerCertPin.Pin pinned = pin.resolve(null, null);
        if (pinned != null) {
            return pinned.getPublicKey();
        }
        SenderCertKeyView key = fetchKey();
        if (key == null || key.getKeyId() == null || key.getPublicKey() == null) {
            return null;
        }
        ServerCertPin.Pin resolved = pin.resolve(key.getKeyId(), key.getPublicKey());
        return resolved == null ? null : resolved.getPublicKey();
    }

    /**
     * 当前设备的证书。服务端未启用、网络失败或响应不完整时返回 null。
     */
    public SenderCertificateView certificate() {
        SenderCertificateView current = cached;
        long now = System.currentTimeMillis();
        if (current != null && now < cachedExpiresAt - CACHE_MARGIN_MS) {
            return current;
        }
        try {
            ObjectRestResponse<SenderCertificateView> response =
                Request.connector(CertFeign.class).sender();
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                log.debug("发送者证书不可用：{}",
                    response == null ? "无响应" : response.getMessage());
                return null;
            }
            SenderCertificateView certificate = response.getData();
            if (certificate.getUsername() == null || certificate.getDeviceId() == null
                || certificate.getIdentityKey() == null || certificate.getSignature() == null
                || certificate.getExpiresAt() == null) {
                log.warn("发送者证书响应不完整，本次降级为旧格式");
                return null;
            }
            cached = certificate;
            cachedExpiresAt = certificate.getExpiresAt();
            return certificate;
        } catch (Exception e) {
            log.debug("获取发送者证书失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 清空缓存。设备标识变化或重新登录后调用，避免继续用旧设备的证书。
     */
    public void invalidateCache() {
        cached = null;
        cachedExpiresAt = 0L;
    }

    private SenderCertKeyView fetchKey() {
        try {
            ObjectRestResponse<SenderCertKeyView> response =
                Request.connector(CertFeign.class).key();
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                log.debug("发送者证书公钥不可用：{}",
                    response == null ? "无响应" : response.getMessage());
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.debug("获取发送者证书公钥失败：{}", e.getMessage());
            return null;
        }
    }
}
