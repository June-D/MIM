package com.junesoon.mim.service;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.DhKeyUtil;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.PreKeyStore;
import com.junesoon.mim.crypto.TrustChangedException;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.feign.PreKeyFeign;
import com.junesoon.mim.model.OneTimePreKeyUpload;
import com.junesoon.mim.model.PreKeyBundle;
import com.junesoon.mim.model.SignedPreKeyUpload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 预密钥生命周期管理（按设备）。
 *
 * <p>三件事：
 * <ol>
 *   <li>登录后初始化：加载本设备预密钥，缺失则生成并上传；</li>
 *   <li>补充：一次性预密钥低于阈值时自动补足；</li>
 *   <li>拉取对方<b>所有设备</b>的预密钥包，并<b>用已固定的身份公钥验证签名</b>后才返回。</li>
 * </ol>
 *
 * <p>签名验证是抗中间人的关键：即便服务端返回伪造的预密钥包，
 * 攻击者也没有对方的身份私钥来伪造签名。
 */
@Slf4j
public class PreKeyManager {

    private static final PreKeyManager INSTANCE = new PreKeyManager();

    private static final int REPLENISH_THRESHOLD = 20;
    private static final int REPLENISH_BATCH = 50;

    public static PreKeyManager getInstance() {
        return INSTANCE;
    }

    private final PreKeyStore preKeyStore = new PreKeyStore();
    private final KeyManager keyManager = KeyManager.getInstance();

    private volatile String selfUsername;

    /**
     * 当前设备标识。预密钥按设备隔离，必须记住它，
     * 否则保存时会落到错误的文件。
     */
    private volatile String selfDeviceId;

    /**
     * 登录后初始化本设备的预密钥。
     */
    public synchronized void initialize(String username, String password) throws IdentityException {
        this.selfUsername = username;
        // 必须按「当前账号 + 当前设备」加载：设备标识决定密钥归属，
        // 若沿用缺省的 "default"，新设备会读到别的设备的预密钥文件，
        // 从而误判"已有签名预密钥"而跳过上传，导致服务端没有本设备的预密钥，
        // 别人发消息时就找不到这个设备（表现为对方收不到/解不开）。
        String device = keyManager.deviceId();
        if (device == null || device.isEmpty()) {
            throw new IdentityException("设备标识未初始化，无法初始化预密钥");
        }
        this.selfDeviceId = device;
        preKeyStore.load(username, device, password);

        IdentityStore.Identity identity = keyManager.getIdentity();
        if (identity == null) {
            throw new IdentityException("身份密钥未初始化，无法生成预密钥");
        }

        PreKeyStore.LocalSignedPreKey signed = preKeyStore.getSignedPreKey();
        if (signed == null) {
            signed = preKeyStore.generateSignedPreKey(identity.getPrivateKey(), 1);
            uploadSignedPreKey(signed);
            preKeyStore.setSignedPreKey(signed);
            log.info("已生成并上传签名预密钥 id={}", signed.getPreKeyId());
        }

        replenishIfNeeded(password);
        preKeyStore.save(username, selfDeviceId, password);
    }

    /**
     * 服务端余量不足时补充一次性预密钥。
     */
    public synchronized void replenishIfNeeded(String password) throws IdentityException {
        int remoteCount = fetchRemoteCount();
        if (remoteCount >= REPLENISH_THRESHOLD) {
            return;
        }
        List<PreKeyStore.LocalOneTimePreKey> generated =
            preKeyStore.generateOneTimePreKeys(REPLENISH_BATCH);
        List<OneTimePreKeyUpload> payload = new ArrayList<>(generated.size());
        for (PreKeyStore.LocalOneTimePreKey key : generated) {
            payload.add(new OneTimePreKeyUpload(key.getPreKeyId(), key.getPublicKeyBase64()));
        }
        try {
            ObjectRestResponse<Integer> response =
                Request.connector(PreKeyFeign.class).uploadOneTime(payload);
            if (response == null || response.getStatus() != 200 || response.getData() == null
                || response.getData() <= 0) {
                log.warn("一次性预密钥上传失败，本次会话将退化为不含 DH4 的 X3DH");
                return;
            }
        } catch (Exception e) {
            log.warn("一次性预密钥上传异常：{}", e.getMessage());
            return;
        }
        // 上传成功才在本地保留私钥，否则会出现「本地有私钥但服务端没有」的孤立记录
        preKeyStore.addOneTimePreKeys(generated);
        if (selfUsername != null) {
            preKeyStore.save(selfUsername, selfDeviceId, password);
        }
        log.info("已补充 {} 个一次性预密钥（服务端原有 {}）", generated.size(), remoteCount);
    }

    /**
     * 轮换签名预密钥。
     */
    public synchronized void rotateSignedPreKey(String password) throws IdentityException {
        IdentityStore.Identity identity = keyManager.getIdentity();
        if (identity == null) {
            throw new IdentityException("身份密钥未初始化");
        }
        PreKeyStore.LocalSignedPreKey current = preKeyStore.getSignedPreKey();
        int nextId = (current == null ? 0 : current.getPreKeyId()) + 1;
        PreKeyStore.LocalSignedPreKey rotated =
            preKeyStore.generateSignedPreKey(identity.getPrivateKey(), nextId);
        uploadSignedPreKey(rotated);
        preKeyStore.setSignedPreKey(rotated);
        if (selfUsername != null) {
            preKeyStore.save(selfUsername, selfDeviceId, password);
        }
        log.info("签名预密钥已轮换到 id={}", nextId);
    }

    /**
     * 拉取对方<b>所有有效设备</b>的预密钥包，逐个验签。
     *
     * @return 每个设备一个包；某个设备验签失败会整体抛异常（不应静默跳过，
     *         因为那可能正是攻击者想让我们"忽略"的设备）
     */
    public List<PreKeyBundle> fetchVerifiedBundles(String peer) throws IdentityException {
        return fetchVerifiedBundles(peer, currentPassword());
    }

    private List<PreKeyBundle> fetchVerifiedBundles(String peer, String password)
        throws IdentityException {
        List<PreKeyBundle> bundles;
        try {
            ObjectRestResponse<List<PreKeyBundle>> response =
                Request.connector(PreKeyFeign.class).fetch(peer);
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException(error == null ? "获取预密钥包失败" : error);
            }
            bundles = response.getData();
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("获取预密钥包失败：" + e.getMessage());
        }
        if (bundles == null || bundles.isEmpty()) {
            return Collections.emptyList();
        }

        TrustStore trustStore = keyManager.getTrustStore();
        for (PreKeyBundle bundle : bundles) {
            // TOFU 校验设备身份公钥
            TrustStore.VerificationResult trust = trustStore.verify(peer,
                bundle.getIdentityPublicKey(), password);
            if (!trust.isSendable()) {
                // 公钥变更：抛专用异常，让界面提供「核对后接受」的出口。
                // 若只抛一般错误，用户点掉弹窗后永久发不出消息。
                throw new TrustChangedException(peer, trust.getPreviousFingerprint(),
                    trust.getCurrentFingerprint(), bundle.getIdentityPublicKey(),
                    trust.describe());
            }
            // 用已固定的身份公钥验证签名预密钥签名
            boolean valid = DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
                bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
                bundle.getSignedPreKeySignature());
            if (!valid) {
                throw new IdentityException("对方设备 " + bundle.getDeviceId()
                    + " 的预密钥签名验证失败，可能遭到中间人攻击，已中止");
            }
            log.debug("用户 {} 设备 {} 预密钥包验签通过，OPK={}", peer, bundle.getDeviceId(),
                bundle.hasOneTimePreKey() ? bundle.getOneTimePreKeyId() : "无");
        }
        return bundles;
    }

    /**
     * 领取本账号某一台设备的预密钥包。只验签名，不把这把公钥记到账号名的信任库里。
     */
    public PreKeyBundle fetchOwnDevice(String deviceId) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("预密钥尚未初始化");
        }
        List<PreKeyBundle> bundles;
        try {
            ObjectRestResponse<List<PreKeyBundle>> response =
                Request.connector(PreKeyFeign.class).fetchDevice(selfUsername, deviceId);
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException(error == null ? "获取本机设备预密钥失败" : error);
            }
            bundles = response.getData();
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("获取本机设备预密钥失败：" + e.getMessage());
        }
        if (bundles == null || bundles.isEmpty()) {
            throw new IdentityException("本账号的设备 " + deviceId + " 还没有预密钥");
        }
        PreKeyBundle bundle = bundles.get(0);
        boolean valid = DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature());
        if (!valid) {
            throw new IdentityException("本账号设备 " + deviceId + " 的预密钥签名验证失败");
        }
        return bundle;
    }

    public PreKeyStore getPreKeyStore() {
        return preKeyStore;
    }

    /**
     * 用户在核对指纹后确认接受对方的新身份公钥。
     *
     * <p>这是 TOFU 的必要出口：安全机制负责<b>告警</b>，
     * 最终判断「是不是真的换了设备」只能由人通过其他可信渠道完成。
     */
    public void acceptTrustChange(String peer, String newPublicKey) throws IdentityException {
        String password = currentPassword();
        keyManager.getTrustStore().acceptChange(peer, newPublicKey, password);
        log.warn("用户确认接受 {} 的新身份公钥，后续发送将使用该密钥", peer);
    }

    public void reset() {
        preKeyStore.clear();
        selfUsername = null;
        selfDeviceId = null;
    }

    private String currentPassword() throws IdentityException {
        char[] password = com.junesoon.mim.components.ApplicatonStore.getSessionPassword();
        if (password == null || password.length == 0) {
            throw new IdentityException("会话已锁定，请重新登录");
        }
        return new String(password);
    }

    private void uploadSignedPreKey(PreKeyStore.LocalSignedPreKey key) throws IdentityException {
        SignedPreKeyUpload request = new SignedPreKeyUpload();
        request.setPreKeyId(key.getPreKeyId());
        request.setPublicKey(key.getPublicKeyBase64());
        request.setSignature(key.getSignatureBase64());
        try {
            ObjectRestResponse<Map<String, Object>> response =
                Request.connector(PreKeyFeign.class).uploadSigned(request);
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException("上传签名预密钥失败：" + error);
            }
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("上传签名预密钥失败：" + e.getMessage());
        }
    }

    private int fetchRemoteCount() {
        try {
            ObjectRestResponse<Map<String, Object>> response =
                Request.connector(PreKeyFeign.class).status();
            if (response == null || response.getData() == null) {
                return Integer.MAX_VALUE;
            }
            Object value = response.getData().get("oneTimePreKeys");
            return value == null ? Integer.MAX_VALUE : Integer.parseInt(value.toString());
        } catch (Exception e) {
            // 查不到就当作充足，避免每次登录都重复上传
            return Integer.MAX_VALUE;
        }
    }
}
