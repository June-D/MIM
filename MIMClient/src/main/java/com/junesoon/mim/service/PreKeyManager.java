package com.junesoon.mim.service;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.DhKeyUtil;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.PreKeyUnavailableException;
import com.junesoon.mim.crypto.PreKeyStore;
import com.junesoon.mim.crypto.TrustChangedException;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.feign.KeyFeign;
import com.junesoon.mim.feign.PreKeyFeign;
import com.junesoon.mim.model.KeyBundle;
import com.junesoon.mim.model.OneTimePreKeyUpload;
import com.junesoon.mim.model.PreKeyBundle;
import com.junesoon.mim.model.SignedPreKeyUpload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 签名预密钥最长使用时间。定期轮换可以限制它在泄露后的影响窗口；
     * 旧私钥会保留在本地，直到在途握手全部完成。
     */
    private static final long SPK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

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
        } else if (signed.getCreatedAt() == 0
            || System.currentTimeMillis() - signed.getCreatedAt() > SPK_MAX_AGE_MS) {
            // 旧版本文件没有 createdAt，按过期处理：轮换一次，同时保留旧私钥
            log.info("签名预密钥 id={} 已超过轮换周期，开始轮换", signed.getPreKeyId());
            rotateSignedPreKey(password);
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
     * 拉取对方<b>所有有效设备</b>的身份公钥，并完成 TOFU 校验。
     *
     * <p>这个接口<b>不消耗一次性预密钥</b>：先确定对方有哪些设备，
     * 再只对「本地还没有会话」的设备领取预密钥包。以前每条消息都领取全部设备的包，
     * 会导致对方的 OPK 被无谓耗尽。
     *
     * <p>首次见到该用户时会一次性固定其全部设备；已固定用户新增设备时抛
     * {@link TrustChangedException}，由用户确认后再继续。
     */
    public List<KeyBundle> fetchVerifiedDevices(String peer) throws IdentityException {
        return fetchVerifiedDevices(peer, currentPassword());
    }

    private List<KeyBundle> fetchVerifiedDevices(String peer, String password)
        throws IdentityException {
        List<KeyBundle> keys;
        try {
            ObjectRestResponse<List<KeyBundle>> response =
                Request.connector(KeyFeign.class).getDevices(peer);
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException(error == null ? "获取对方设备列表失败" : error);
            }
            keys = response.getData();
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("获取对方设备列表失败：" + e.getMessage());
        }
        if (keys == null || keys.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        Map<String, String> deviceKeys = new LinkedHashMap<>();
        for (KeyBundle key : keys) {
            if (key == null || key.getDeviceId() == null || key.getDeviceId().isEmpty()
                || key.getPublicKey() == null || key.getPublicKey().isEmpty()) {
                throw new IdentityException("服务端返回了不完整的设备公钥");
            }
            try {
                java.util.Base64.getDecoder().decode(key.getPublicKey());
            } catch (IllegalArgumentException e) {
                throw new IdentityException("服务端返回了非法的设备公钥");
            }
            deviceKeys.put(key.getDeviceId(), key.getPublicKey());
        }

        List<TrustStore.VerificationResult> results =
            keyManager.getTrustStore().verifyAll(peer, deviceKeys, password);
        throwIfUntrusted(peer, results);
        return keys;
    }

    /**
     * 只为指定设备领取预密钥包，并在使用前验证签名与信任状态。
     *
     * <p>顺序很重要：<b>先验签，再进入信任库</b>。旧实现先把公钥固定下来再验签，
     * 攻击者可以用一个签名错误的包污染信任库，让真实公钥此后永远显示 CHANGED。
     */
    public PreKeyBundle fetchVerifiedBundle(String peer, String deviceId) throws IdentityException {
        String password = currentPassword();
        List<PreKeyBundle> bundles;
        try {
            ObjectRestResponse<List<PreKeyBundle>> response =
                Request.connector(PreKeyFeign.class).fetchDevice(peer, deviceId);
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
            throw new PreKeyUnavailableException(
                "对方设备 " + deviceId + " 尚未发布预密钥，已跳过该设备");
        }
        PreKeyBundle bundle = bundles.get(0);
        if (bundle == null || bundle.getIdentityPublicKey() == null) {
            throw new PreKeyUnavailableException(
                "对方设备 " + deviceId + " 的预密钥包不完整，已跳过该设备");
        }

        // 1. 先用包里声明的身份公钥验签，确认包本身自洽
        if (!DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature())) {
            throw new IdentityException("对方设备 " + deviceId
                + " 的预密钥签名验证失败，可能遭到中间人攻击，已中止");
        }

        // 2. 再与本地固定值比对；首次接触在这里固定
        TrustStore.VerificationResult trust = keyManager.getTrustStore()
            .verify(peer, deviceId, bundle.getIdentityPublicKey(), password);
        if (!trust.isSendable()) {
            throw toTrustChanged(peer, trust);
        }
        if (!trust.getPublicKey().equals(bundle.getIdentityPublicKey())) {
            throw new IdentityException("对方设备 " + deviceId + " 的身份公钥不一致，已中止");
        }
        return bundle;
    }

    /**
     * 领取本账号某一台设备的预密钥包。
     *
     * <p>这里只做<b>包内自洽性</b>验签，不代表已经信任这台设备。历史同步必须先
     * 用 {@link com.junesoon.mim.crypto.OwnDeviceTrust} 判断是否已授权；
     * 未授权时向用户展示指纹，确认后再调用 {@link #verifyOwnDeviceBundle} 按
     * 已固定公钥复核，最后才允许转发明文。
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
        if (bundle == null || bundle.getIdentityPublicKey() == null) {
            throw new IdentityException("本账号设备 " + deviceId + " 的预密钥包不完整");
        }
        if (!DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature())) {
            throw new IdentityException("本账号设备 " + deviceId + " 的预密钥签名验证失败");
        }
        return bundle;
    }

    /**
     * 用本机已授权的设备公钥复核一个预密钥包。历史同步在真正加密前必须调用。
     */
    public void verifyOwnDeviceBundle(PreKeyBundle bundle) throws IdentityException {
        if (bundle == null || bundle.getDeviceId() == null) {
            throw new IdentityException("目标设备信息不完整");
        }
        String pinned = keyManager.getOwnDeviceTrust().getPinned(bundle.getDeviceId());
        if (pinned == null) {
            throw new IdentityException("本账号设备 " + bundle.getDeviceId()
                + " 尚未获得本机授权，拒绝转发历史");
        }
        if (!pinned.equals(bundle.getIdentityPublicKey())) {
            throw new IdentityException("本账号设备 " + bundle.getDeviceId()
                + " 的身份公钥发生变化，拒绝转发历史");
        }
        if (!DhKeyUtil.verifySignedPreKey(pinned, bundle.getSignedPreKeyId(),
            bundle.getSignedPreKeyPublic(), bundle.getSignedPreKeySignature())) {
            throw new IdentityException("本账号设备 " + bundle.getDeviceId()
                + " 的预密钥签名验证失败，拒绝转发历史");
        }
    }

    public PreKeyStore getPreKeyStore() {
        return preKeyStore;
    }

    /**
     * 用户核对指纹后确认接受对方一组设备的新公钥。
     *
     * <p>这是 TOFU 的必要出口：安全机制负责<b>告警</b>，
     * 最终判断「是不是真的换了设备」只能由人通过其他可信渠道完成。
     */
    public void acceptTrustChanges(TrustChangedException exception) throws IdentityException {
        String password = currentPassword();
        Map<String, String> updates = new LinkedHashMap<>();
        for (TrustChangedException.Issue issue : exception.getIssues()) {
            updates.put(issue.getDeviceId(), issue.getNewPublicKey());
        }
        keyManager.getTrustStore().acceptDevices(exception.getPeer(), updates, password);
        log.warn("用户确认接受 {} 的 {} 台设备", exception.getPeer(), updates.size());
    }

    private void throwIfUntrusted(String peer, List<TrustStore.VerificationResult> results)
        throws TrustChangedException {
        List<TrustChangedException.Issue> issues = new ArrayList<>();
        for (TrustStore.VerificationResult result : results) {
            if (!result.isSendable()) {
                issues.add(new TrustChangedException.Issue(result.getDeviceId(),
                    result.getStatus() == TrustStore.TrustStatus.NEW_DEVICE
                        ? TrustChangedException.Reason.NEW_DEVICE
                        : TrustChangedException.Reason.KEY_CHANGED,
                    result.getPreviousFingerprint(), result.getCurrentFingerprint(),
                    result.getPublicKey()));
            }
        }
        if (!issues.isEmpty()) {
            throw new TrustChangedException(peer, issues,
                "对方设备身份需要确认：共 " + issues.size() + " 台设备");
        }
    }

    private TrustChangedException toTrustChanged(String peer, TrustStore.VerificationResult result) {
        TrustChangedException.Reason reason =
            result.getStatus() == TrustStore.TrustStatus.NEW_DEVICE
                ? TrustChangedException.Reason.NEW_DEVICE
                : TrustChangedException.Reason.KEY_CHANGED;
        List<TrustChangedException.Issue> issues = new ArrayList<>(1);
        issues.add(new TrustChangedException.Issue(result.getDeviceId(), reason,
            result.getPreviousFingerprint(), result.getCurrentFingerprint(),
            result.getPublicKey()));
        return new TrustChangedException(peer, issues, result.describe());
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
