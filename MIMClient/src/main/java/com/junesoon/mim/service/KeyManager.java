package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.OwnDeviceTrust;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.feign.DeviceFeign;
import com.junesoon.mim.feign.KeyFeign;
import com.junesoon.mim.feign.KeyUploadRequest;
import com.junesoon.mim.model.KeyBundle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端加密身份管理（多设备）。
 *
 * <p>职责：
 * <ol>
 *   <li>登录后确保本机有设备标识，并向服务端登记；</li>
 *   <li>确保本地有一对 EC P-256 身份密钥（不存在则生成）；</li>
 *   <li>把本设备公钥同步到服务端；</li>
 *   <li>维护 TOFU 信任库。</li>
 * </ol>
 *
 * <p>私钥永远不出本机；服务端只见到公钥与指纹。
 */
@Slf4j
public class KeyManager {

    private static final KeyManager INSTANCE = new KeyManager();

    public static KeyManager getInstance() {
        return INSTANCE;
    }

    private final IdentityStore identityStore = new IdentityStore();
    private final TrustStore trustStore = new TrustStore();
    private final OwnDeviceTrust ownDeviceTrust = new OwnDeviceTrust();
    private final DeviceIdentity deviceIdentity = new DeviceIdentity();

    private volatile IdentityStore.Identity identity;

    public IdentityStore.Identity getIdentity() {
        return identity;
    }

    public TrustStore getTrustStore() {
        return trustStore;
    }

    /**
     * 本账号自己的设备清单（只由用户显式批准）。历史同步只允许发给其中的设备。
     */
    public OwnDeviceTrust getOwnDeviceTrust() {
        return ownDeviceTrust;
    }

    /**
     * 当前账号在本机的设备标识。
     *
     * @return 未登录时返回 null，避免在登录前误生成"匿名设备"
     */
    public String deviceId() {
        String username = ApplicatonStore.getUsername();
        if (username == null) {
            return deviceIdentity.current();
        }
        return deviceIdentity.getOrCreate(username);
    }

    /**
     * 登录成功后初始化：注册设备 -> 加载/生成身份密钥 -> 同步公钥 -> 加载信任库。
     */
    public synchronized KeyBundle initialize(String username, String password) throws IdentityException {
        // 1. 设备必须先注册：服务端拒绝未登记设备的公钥上传
        String device = deviceIdentity.getOrCreate(username);
        ApplicatonStore.setDeviceId(device);
        registerDevice(username, device);

        // 2. 加载或生成身份密钥
        IdentityStore.Identity loaded = identityStore.load(username, device, password);
        if (loaded == null) {
            int version = 1;
            KeyBundle remote = fetchSelfQuietly();
            if (remote != null && remote.getVersion() != null) {
                // 服务端已有公钥但本地私钥丢了：无法恢复历史消息，
                // 只能换新钥并把版本推进到服务端之上，同时明确提示用户。
                version = remote.getVersion() + 1;
                log.warn("本地私钥缺失，将为用户 {} 设备 {} 生成新密钥对，版本 {}；历史消息将无法解密",
                    username, device, version);
            }
            loaded = identityStore.create(username, device, password, version);
        }
        this.identity = loaded;

        // 3. 加载 TOFU 信任库（之后每次使用对方身份公钥都要比对）
        trustStore.load(username, password);

        // 4. 加载本账号设备信任清单，并信任当前设备自己。
        //    其它设备必须由用户确认后才会进入清单——历史同步只发给清单内的设备。
        ownDeviceTrust.load(username, password);
        ownDeviceTrust.trustCurrent(device, loaded.getPublicKeyBase64(), password);

        // 5. 同步本设备公钥到服务端
        return uploadPublicKey(loaded);
    }

    /**
     * 登记设备。幂等，重复调用只刷新活跃时间。
     */
    private void registerDevice(String username, String device) throws IdentityException {
        try {
            ObjectRestResponse<Object> response = Request.connector(DeviceFeign.class)
                .register(device, deviceIdentity.deviceName(username));
            if (response == null || response.getStatus() != 200) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException("设备注册失败：" + message);
            }
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("设备注册失败：" + e.getMessage());
        }
    }

    /**
     * 把本设备公钥同步到服务端。服务端对同一指纹幂等，不会无谓涨版本。
     */
    public KeyBundle uploadPublicKey(IdentityStore.Identity current) throws IdentityException {
        try {
            ObjectRestResponse<KeyBundle> response = Request.connector(KeyFeign.class)
                .upload(new KeyUploadRequest(current.getPublicKeyBase64(), "EC-P256"));
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException("同步公钥失败：" + message);
            }
            return response.getData();
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("同步公钥失败：" + e.getMessage());
        }
    }

    /**
     * 校验服务端返回的公钥指纹与内容是否自洽。
     */
    public boolean verifyFingerprint(KeyBundle bundle) {
        if (bundle == null || bundle.getPublicKey() == null || bundle.getFingerprint() == null) {
            return false;
        }
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(bundle.getPublicKey());
            return bundle.getFingerprint().equalsIgnoreCase(Hkdf.sha256Hex(raw));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 同步来的密文必须由本账号已登记设备的身份公钥签名，避免把别人伪造的历史当成好友消息。
     */
    public void assertDeviceIdentity(String deviceId, String publicKey) throws IdentityException {
        if (publicKey == null || publicKey.isEmpty()) {
            throw new IdentityException("同步消息缺少来源设备的身份公钥");
        }
        if (identity == null) {
            throw new IdentityException("本地加密身份尚未初始化");
        }
        try {
            ObjectRestResponse<List<KeyBundle>> response = Request.connector(KeyFeign.class)
                .getDevice(identity.getUsername(), deviceId);
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                throw new IdentityException("无法核对本账号设备的身份公钥");
            }
            for (KeyBundle bundle : response.getData()) {
                if (bundle != null && publicKey.equals(bundle.getPublicKey())) {
                    return;
                }
            }
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("无法核对本账号设备的身份公钥：" + e.getMessage());
        }
        throw new IdentityException("同步来源不是本账号的设备");
    }

    /**
     * 用新口令重新加密本机身份、信任库与设备授权清单。
     */
    public synchronized void changePassword(String newPassword) throws IdentityException {
        IdentityStore.Identity current = identity;
        if (current == null) {
            throw new IdentityException("本地身份密钥尚未初始化");
        }
        identityStore.saveExisting(current, current.getUsername(), deviceId(), newPassword);
        trustStore.changePassword(newPassword);
        ownDeviceTrust.changePassword(newPassword);
    }

    public void reset() {
        identity = null;
        trustStore.reset();
        ownDeviceTrust.reset();
        // 设备标识按账号隔离，切换账号必须清缓存，
        // 否则新账号会沿用旧账号的 device_id，两个账号共用一份密钥记录
        deviceIdentity.reset();
    }

    private KeyBundle fetchSelfQuietly() {
        try {
            ObjectRestResponse<KeyBundle> response = Request.connector(KeyFeign.class).self();
            if (response != null && response.getStatus() == 200) {
                return response.getData();
            }
        } catch (Exception e) {
            log.debug("查询服务端公钥失败，按首次使用处理：{}", e.getMessage());
        }
        return null;
    }
}
