package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.PreKeyMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.service.PreKeyService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 预密钥包服务。
 *
 * <p>重要的信任边界：<b>服务端不验签</b>。签名由客户端生成、由接收方用
 * 已固定的身份公钥验证。服务端若也验签，等于让服务端参与信任链，
 * 反而削弱了端到端的含义（服务端被攻破时可以伪造签名通过自验）。
 *
 * <p>服务端只做结构校验：长度、Base64 合法性、数量上限。
 */
@Slf4j
@Service
public class PreKeyServiceImpl implements PreKeyService {

    private static final int MAX_SIGNED_PREKEY_BYTES = 1024;
    private static final int MAX_SIGNATURE_BYTES = 512;
    private static final int MAX_ONE_TIME_PREKEYS = 200;
    private static final int MIN_PUBLIC_KEY_BYTES = 32;
    private static final int MIN_SIGNATURE_BYTES = 32;

    /**
     * 预留 OPK 的超时时间。发送方领取后应在秒级内发送；超时未用则释放，
     * 避免“领取后不发送”把对方的密钥永久占住。
     */
    private static final long CLAIM_TTL_MS = 10L * 60 * 1000;

    private final PreKeyMapper preKeyMapper;
    private final UserKeyMapper userKeyMapper;
    private final DeviceMapper deviceMapper;

    public PreKeyServiceImpl(PreKeyMapper preKeyMapper, UserKeyMapper userKeyMapper,
        DeviceMapper deviceMapper) {
        this.preKeyMapper = preKeyMapper;
        this.userKeyMapper = userKeyMapper;
        this.deviceMapper = deviceMapper;
    }

    @Override
    public SignedPreKey uploadSignedPreKey(String username, String deviceId, SignedPreKey request) {
        ValidationUtil.validateUsername(username);
        String device = requireDevice(username, deviceId);
        if (request == null) {
            throw new UserInvalidException("预密钥不能为空");
        }
        if (request.getPreKeyId() == null || request.getPreKeyId() < 1) {
            throw new UserInvalidException("预密钥编号不合法");
        }
        if (!StringUtils.hasText(request.getPublicKey())) {
            throw new UserInvalidException("预密钥公钥不能为空");
        }
        if (!StringUtils.hasText(request.getSignature())) {
            throw new UserInvalidException("预密钥签名不能为空");
        }
        byte[] publicKeyBytes = decode(request.getPublicKey(), "预密钥公钥", MAX_SIGNED_PREKEY_BYTES);
        byte[] signatureBytes = decode(request.getSignature(), "预密钥签名", MAX_SIGNATURE_BYTES);
        if (publicKeyBytes.length < MIN_PUBLIC_KEY_BYTES) {
            throw new UserInvalidException("预密钥公钥长度不合法");
        }
        if (signatureBytes.length < MIN_SIGNATURE_BYTES) {
            throw new UserInvalidException("预密钥签名长度不合法");
        }
        // 必须先有该设备的身份公钥，否则接收方无法验证签名
        requireDeviceIdentity(username, device);

        long now = System.currentTimeMillis();
        SignedPreKey key = new SignedPreKey();
        key.setUsername(username);
        key.setDeviceId(device);
        key.setPreKeyId(request.getPreKeyId());
        key.setPublicKey(request.getPublicKey());
        key.setSignature(request.getSignature());
        key.setCrtTime(now);
        key.setUpdTime(now);
        preKeyMapper.upsertSignedPreKey(key);
        log.info("用户 {} 设备 {} 更新签名预密钥 id={}", username, device, request.getPreKeyId());
        return key;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int uploadOneTimePreKeys(String username, String deviceId, List<OneTimePreKey> keys) {
        ValidationUtil.validateUsername(username);
        String device = requireDevice(username, deviceId);
        if (keys == null || keys.isEmpty()) {
            throw new UserInvalidException("一次性预密钥不能为空");
        }
        if (keys.size() > MAX_ONE_TIME_PREKEYS) {
            throw new UserInvalidException("单次最多上传 " + MAX_ONE_TIME_PREKEYS + " 个一次性预密钥");
        }
        requireDeviceIdentity(username, device);

        long now = System.currentTimeMillis();
        List<OneTimePreKey> batch = new ArrayList<>(keys.size());
        for (OneTimePreKey item : keys) {
            if (item == null || item.getPreKeyId() == null || item.getPreKeyId() < 1) {
                throw new UserInvalidException("预密钥编号不合法");
            }
            if (!StringUtils.hasText(item.getPublicKey())) {
                throw new UserInvalidException("预密钥公钥不能为空");
            }
            decode(item.getPublicKey(), "预密钥公钥", MAX_SIGNED_PREKEY_BYTES);
            OneTimePreKey key = new OneTimePreKey();
            key.setUsername(username);
            key.setDeviceId(device);
            key.setPreKeyId(item.getPreKeyId());
            key.setPublicKey(item.getPublicKey());
            key.setCrtTime(now);
            batch.add(key);
        }
        return preKeyMapper.batchInsertOneTimePreKeys(batch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PreKeyBundle> fetchBundles(String username, String requester) {
        ValidationUtil.validateUsername(username);

        List<Device> devices = deviceMapper.selectActiveDevices(username);
        if (devices == null || devices.isEmpty()) {
            throw new UserInvalidException("对方尚未注册任何设备，无法发起加密会话");
        }

        List<PreKeyBundle> bundles = new ArrayList<>(devices.size());
        for (Device device : devices) {
            PreKeyBundle bundle = buildBundle(username, device.getDeviceId(), requester);
            if (bundle == null) {
                log.debug("用户 {} 设备 {} 尚未发布预密钥，跳过", username, device.getDeviceId());
                continue;
            }
            bundles.add(bundle);
        }

        if (bundles.isEmpty()) {
            throw new UserInvalidException("对方尚未发布预密钥，无法发起加密会话");
        }
        return bundles;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreKeyBundle fetchBundle(String username, String deviceId, String requester) {
        ValidationUtil.validateUsername(username);
        String device = requireDevice(username, deviceId);
        PreKeyBundle bundle = buildBundle(username, device, requester);
        if (bundle == null) {
            throw new UserInvalidException("该设备尚未发布预密钥");
        }
        return bundle;
    }

    /**
     * 组装一台设备的预密钥包，并把一个 OPK 预留給请求者。
     *
     * <p>不再在领取时删除：消息真正使用 OPK 后由消息服务删除；同一请求者重试
     * 会拿回同一把，避免重复消耗；超过 TTL 未使用的预留会被释放。
     */
    private PreKeyBundle buildBundle(String username, String deviceId, String requester) {
        SignedPreKey signed = preKeyMapper.selectSignedPreKey(username, deviceId);
        UserKey identity = userKeyMapper.selectByDevice(username, deviceId);
        if (signed == null || identity == null) {
            return null;
        }

        OneTimePreKey oneTime = null;
        if (StringUtils.hasText(requester)) {
            // 同一请求者的重试：复用已经预留的 OPK
            oneTime = preKeyMapper.selectClaimedOneTimePreKey(username, deviceId, requester);
        }
        if (oneTime == null && StringUtils.hasText(requester)) {
            long now = System.currentTimeMillis();
            preKeyMapper.releaseExpiredOneTimePreKeys(username, deviceId, now - CLAIM_TTL_MS);
            // 并发下可能被别人抢走，最多重试几次
            for (int attempt = 0; attempt < 3 && oneTime == null; attempt++) {
                OneTimePreKey candidate =
                    preKeyMapper.selectOneTimePreKeyForUpdate(username, deviceId);
                if (candidate == null) {
                    break;
                }
                if (preKeyMapper.claimOneTimePreKey(candidate.getId(), username, requester, now) > 0) {
                    oneTime = candidate;
                }
            }
        }
        if (oneTime == null) {
            log.debug("用户 {} 设备 {} 一次性预密钥已耗尽，本次会话将省略 DH4", username, deviceId);
        }
        return new PreKeyBundle(username, deviceId, identity.getPublicKey(), identity.getVersion(),
            signed.getPreKeyId(), signed.getPublicKey(), signed.getSignature(),
            oneTime == null ? null : oneTime.getPreKeyId(),
            oneTime == null ? null : oneTime.getPublicKey(),
            Boolean.TRUE.equals(identity.getSupportsRecallToken()),
            Boolean.TRUE.equals(identity.getSupportsSenderCert()));
    }

    @Override
    public int countOneTimePreKeys(String username, String deviceId) {
        ValidationUtil.validateUsername(username);
        return preKeyMapper.countOneTimePreKeys(username, requireDevice(username, deviceId));
    }

    /**
     * 校验设备标识合法且已登记。
     *
     * <p>设备必须先注册：否则任何人都能用任意 deviceId 上传密钥，
     * 污染某用户的设备列表。
     */
    private String requireDevice(String username, String deviceId) {
        String device = StringUtils.hasText(deviceId)
            ? deviceId.trim() : UserKey.DEFAULT_DEVICE_ID;
        if (!device.matches("^[A-Za-z0-9._-]{1,64}$")) {
            throw new UserInvalidException("设备标识不合法");
        }
        Device registered = deviceMapper.selectOne(username, device);
        if (registered == null || Boolean.TRUE.equals(registered.getRevoked())) {
            throw new UserInvalidException("设备未注册或已注销，请先注册设备");
        }
        return device;
    }

    private UserKey requireDeviceIdentity(String username, String device) {
        UserKey key = userKeyMapper.selectByDevice(username, device);
        if (key == null) {
            throw new UserInvalidException("该设备尚未上传身份公钥");
        }
        return key;
    }

    private byte[] decode(String value, String field, int maxBytes) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > maxBytes) {
                throw new UserInvalidException(field + "过长");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException(field + "不是合法的 Base64");
        }
    }
}
