package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.responses.KeyBundle;
import com.junesoon.mim.service.KeyService;
import com.junesoon.mim.utils.ValidationUtil;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 设备端到端加密公钥目录。
 *
 * <p>服务器只做两件事：验证上传的公钥确实是合法的 P-256 公钥，以及维护版本号与指纹。
 * 私钥永远不离开客户端。
 */
@Slf4j
@Service
public class KeyServiceImpl implements KeyService {

    public static final String ALGORITHM = "EC-P256";

    private static final int MAX_PUBLIC_KEY_BYTES = 1024;
    private static final int MIN_PUBLIC_KEY_BYTES = 32;
    private static final int P256_FIELD_SIZE = 256;

    private final UserKeyMapper userKeyMapper;
    private final DeviceMapper deviceMapper;

    public KeyServiceImpl(UserKeyMapper userKeyMapper, DeviceMapper deviceMapper) {
        this.userKeyMapper = userKeyMapper;
        this.deviceMapper = deviceMapper;
    }

    @Override
    public KeyBundle upload(String username, String deviceId, String publicKey, String algorithm) {
        ValidationUtil.validateUsername(username);
        String device = StringUtils.hasText(deviceId)
            ? deviceId.trim() : UserKey.DEFAULT_DEVICE_ID;
        if (!StringUtils.hasText(publicKey)) {
            throw new UserInvalidException("公钥不能为空");
        }
        String alg = StringUtils.hasText(algorithm) ? algorithm : ALGORITHM;
        if (!ALGORITHM.equals(alg)) {
            throw new UserInvalidException("不支持的密钥算法");
        }
        // 只允许给已登记且未注销的设备上传公钥，
        // 否则攻击者可以给他人添加幽灵设备，导致消息被加密到无人持有的密钥
        if (deviceMapper.selectOne(username, device) == null) {
            throw new UserInvalidException("设备未注册，请先注册设备");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException("公钥格式错误");
        }
        if (keyBytes.length < MIN_PUBLIC_KEY_BYTES || keyBytes.length > MAX_PUBLIC_KEY_BYTES) {
            throw new UserInvalidException("公钥长度不合法");
        }
        validateEcP256Key(keyBytes);

        String fingerprint = fingerprint(keyBytes);
        UserKey existing = userKeyMapper.selectByDevice(username, device);
        if (existing != null && fingerprint.equals(existing.getFingerprint())) {
            // 同一把公钥重复上传，保持幂等，不涨版本
            return toBundle(existing);
        }

        UserKey key = existing == null ? new UserKey() : existing;
        key.setUsername(username);
        key.setDeviceId(device);
        key.setPublicKey(publicKey);
        key.setAlgorithm(alg);
        key.setVersion(existing == null || existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        key.setFingerprint(fingerprint);
        key.setUpdTime(new Date());
        if (existing == null) {
            key.setCrtTime(new Date());
        }
        userKeyMapper.upsert(key);
        if (existing != null) {
            log.warn("用户 {} 设备 {} 更换了身份公钥，新版本 {} 指纹 {}",
                username, device, key.getVersion(), fingerprint);
        }
        return toBundle(key);
    }

    @Override
    public KeyBundle get(String username, String deviceId) {
        ValidationUtil.validateUsername(username);
        String device = StringUtils.hasText(deviceId)
            ? deviceId.trim() : UserKey.DEFAULT_DEVICE_ID;
        UserKey key = userKeyMapper.selectByDevice(username, device);
        if (key == null) {
            throw new UserInvalidException("对方尚未建立加密身份，请先让对方登录一次");
        }
        return toBundle(key);
    }

    @Override
    public List<KeyBundle> getAllDevices(String username) {
        ValidationUtil.validateUsername(username);
        List<UserKey> keys = userKeyMapper.selectAllByUsername(username);
        if (keys == null || keys.isEmpty()) {
            throw new UserInvalidException("对方尚未建立加密身份，请先让对方登录一次");
        }
        List<KeyBundle> bundles = new ArrayList<>(keys.size());
        for (UserKey key : keys) {
            bundles.add(toBundle(key));
        }
        return bundles;
    }

    private void validateEcP256Key(byte[] keyBytes) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
            if (!(publicKey instanceof ECPublicKey)) {
                throw new UserInvalidException("公钥类型错误");
            }
            int fieldSize = ((ECPublicKey) publicKey).getParams().getCurve().getField().getFieldSize();
            if (fieldSize != P256_FIELD_SIZE) {
                throw new UserInvalidException("仅支持 P-256 曲线");
            }
        } catch (UserInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new UserInvalidException("公钥无法解析");
        }
    }

    private String fingerprint(byte[] keyBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(keyBytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算公钥指纹", e);
        }
    }

    private KeyBundle toBundle(UserKey key) {
        KeyBundle bundle = new KeyBundle();
        bundle.setUsername(key.getUsername());
        bundle.setDeviceId(key.getDeviceId());
        bundle.setPublicKey(key.getPublicKey());
        bundle.setAlgorithm(key.getAlgorithm());
        bundle.setVersion(key.getVersion());
        bundle.setFingerprint(key.getFingerprint());
        return bundle;
    }
}
