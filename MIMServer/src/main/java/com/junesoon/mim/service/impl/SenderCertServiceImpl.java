package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.jwt.RsaKeyHelper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.responses.SenderCertKey;
import com.junesoon.mim.responses.SenderCertificate;
import com.junesoon.mim.service.RsaKeyService;
import com.junesoon.mim.service.SenderCertService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 发送者证书签发实现（Phase 2a）。
 *
 * <p>签名密钥与 JWT 密钥<b>分离</b>：JWT 私钥泄露不应连带让攻击者
 * 伪造发送者证书，反之亦然。密钥存在 {@code t_rsa_key}，沿用主密钥加密，
 * 首次使用时才生成，功能关闭时不产生任何密钥。
 *
 * [INPUT]: 依赖 RsaKeyService（密钥读写）与 UserKeyMapper（取身份公钥）
 * [OUTPUT]: 提供 key() / issue()，证书字段与签名规范见 SenderCertificate
 * [POS]: 2a 的服务端核心；2b 会把证书塞进首条消息的加密正文
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
@Service
public class SenderCertServiceImpl implements SenderCertService {

    /** 与 JWT 密钥分开存放，避免密钥复用。 */
    private static final String PRI_KEY = "MIM:AUTH:SENDER-CERT:PRI";
    private static final String PUB_KEY = "MIM:AUTH:SENDER-CERT:PUB";

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";

    /** 证书有效期：24 小时。客户端需容忍少量时钟偏差。 */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private final RsaKeyService rsaKeyService;

    private final UserKeyMapper userKeyMapper;

    @Value("${mim.sender-cert.enabled:false}")
    private boolean enabled;

    private volatile PrivateKey privateKey;

    private volatile PublicKey publicKey;

    private volatile String keyId;

    public SenderCertServiceImpl(RsaKeyService rsaKeyService, UserKeyMapper userKeyMapper) {
        this.rsaKeyService = rsaKeyService;
        this.userKeyMapper = userKeyMapper;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public SenderCertKey key() {
        requireEnabled();
        ensureKeys();
        SenderCertKey view = new SenderCertKey();
        view.setKeyId(keyId);
        view.setPublicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        view.setAlgorithm(KEY_ALGORITHM);
        return view;
    }

    @Override
    public SenderCertificate issue(String username, String deviceId) {
        requireEnabled();
        UserKey identity = userKeyMapper.selectByDevice(username, deviceId);
        if (identity == null || identity.getPublicKey() == null) {
            throw new UserInvalidException("本设备尚未上传加密公钥，无法签发证书");
        }
        ensureKeys();

        long issuedAt = System.currentTimeMillis();
        SenderCertificate certificate = new SenderCertificate();
        certificate.setUsername(username);
        certificate.setDeviceId(deviceId);
        certificate.setIdentityKey(identity.getPublicKey());
        certificate.setIssuedAt(issuedAt);
        certificate.setExpiresAt(issuedAt + TTL_MS);
        certificate.setKeyId(keyId);
        certificate.setSignature(sign(certificate.canonical()));
        return certificate;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new UserInvalidException("发送者证书功能未启用");
        }
    }

    private String sign(String canonical) {
        try {
            Signature signer = Signature.getInstance(SIGN_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("发送者证书签名失败", e);
        }
    }

    /**
     * 惰性加载：只有真正签发/查询时才读密钥库，功能关闭的实例不会留下密钥行。
     */
    private void ensureKeys() {
        if (privateKey != null && publicKey != null) {
            return;
        }
        synchronized (this) {
            if (privateKey != null && publicKey != null) {
                return;
            }
            try {
                byte[] publicBytes;
                byte[] privateBytes;
                // 历史库可能只有其中一把，缺任意一把都重新生成，避免半对密钥。
                if (rsaKeyService.hasKey(PRI_KEY) && rsaKeyService.hasKey(PUB_KEY)) {
                    publicBytes = RsaKeyHelper.decode(rsaKeyService.get(PUB_KEY));
                    privateBytes = RsaKeyHelper.decode(rsaKeyService.get(PRI_KEY));
                } else {
                    Map<String, byte[]> generated = RsaKeyHelper.generateKey();
                    publicBytes = generated.get("pub");
                    privateBytes = generated.get("pri");
                    rsaKeyService.set(PRI_KEY, RsaKeyHelper.encode(privateBytes));
                    rsaKeyService.set(PUB_KEY, RsaKeyHelper.encode(publicBytes));
                    log.info("生成发送者证书 RSA {} 位密钥对", RsaKeyHelper.KEY_SIZE);
                }
                publicKey = RsaKeyHelper.getPublicKey(publicBytes);
                privateKey = RsaKeyHelper.getPrivateKey(privateBytes);
                keyId = sha256Hex(publicBytes);
            } catch (Exception e) {
                throw new IllegalStateException("发送者证书密钥不可用", e);
            }
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
