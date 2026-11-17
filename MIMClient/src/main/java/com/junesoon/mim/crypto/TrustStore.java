package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * TOFU（Trust On First Use）信任库，按<b>设备</b>固定身份公钥。
 *
 * <p>多设备下身份密钥是按设备独立的：同一个用户的两台设备有两把不同的身份公钥。
 * 如果只按用户名固定，第二台设备必然被判成「公钥变更」，既发不出消息，
 * 也会把用户训练成无脑点击「确认」。因此这里的键是
 * {@code (username, deviceId)}。
 *
 * <p>规则：
 * <ul>
 *   <li>第一次见到某个用户：把它当前<b>所有</b>设备的公钥一次性固定（首次使用即信任）；</li>
 *   <li>已固定用户新增设备：返回 {@link TrustStatus#NEW_DEVICE}，<b>不自动固定</b>，
 *       必须由用户核对指纹后显式确认；</li>
 *   <li>已固定设备的公钥变化：返回 {@link TrustStatus#CHANGED}，阻断发送；</li>
 *   <li>旧版本按用户名保存的固定值会在首次使用时迁移到对应设备，
 *       不会因为升级而把所有联系人重置成 NEW。</li>
 * </ul>
 *
 * <p>局限与 SSH 的 known_hosts 相同：首次使用本身仍可能被中间人劫持，
 * 所以需要配合界面展示的指纹让用户线下核对。
 */
@Slf4j
public class TrustStore {

    /**
     * 信任状态。
     */
    public enum TrustStatus {
        /** 首次见到该用户，已固定其当前所有设备 */
        NEW,
        /** 与固定值一致 */
        TRUSTED,
        /** 已固定用户新增了一台设备，需要用户确认 */
        NEW_DEVICE,
        /** 与固定值不一致，必须人工确认 */
        CHANGED
    }

    /**
     * 验证结果。
     */
    public static final class VerificationResult {
        private final TrustStatus status;
        private final String peer;
        private final String deviceId;
        private final String previousFingerprint;
        private final String currentFingerprint;
        private final String publicKey;

        VerificationResult(TrustStatus status, String peer, String deviceId,
            String previousFingerprint, String currentFingerprint, String publicKey) {
            this.status = status;
            this.peer = peer;
            this.deviceId = deviceId;
            this.previousFingerprint = previousFingerprint;
            this.currentFingerprint = currentFingerprint;
            this.publicKey = publicKey;
        }

        public TrustStatus getStatus() {
            return status;
        }

        public String getPeer() {
            return peer;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getPreviousFingerprint() {
            return previousFingerprint;
        }

        public String getCurrentFingerprint() {
            return currentFingerprint;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public boolean isSendable() {
            return status == TrustStatus.NEW || status == TrustStatus.TRUSTED;
        }

        /**
         * 给用户看的说明。
         */
        public String describe() {
            switch (status) {
                case NEW:
                    return "首次与对方设备 " + deviceId + " 建立加密身份，已固定其公钥指纹 "
                        + shortFingerprint(currentFingerprint);
                case TRUSTED:
                    return "对方设备 " + deviceId + " 的公钥指纹与本地记录一致";
                case NEW_DEVICE:
                    return "⚠ 对方新增了设备 " + deviceId + "。\n指纹："
                        + shortFingerprint(currentFingerprint)
                        + "\n请通过其他可信渠道与对方核对；确认不是中间人后再继续。";
                default:
                    return "⚠ 对方设备 " + deviceId + " 的公钥已变更！\n原指纹："
                        + shortFingerprint(previousFingerprint)
                        + "\n新指纹：" + shortFingerprint(currentFingerprint)
                        + "\n这可能是对方换了设备，也可能是中间人攻击。"
                        + "请通过其他可信渠道与对方核对指纹后再确认。";
            }
        }
    }

    private static final char FIELD_SEP = '\t';
    private static final String RECORD_DEVICE = "d";
    private static final String RECORD_LEGACY = "l";
    private static final String DEFAULT_DEVICE = "default";

    private final Path directory;

    /**
     * 已固定的设备公钥：{@code peer + '\t' + deviceId -> Base64(publicKey)}。
     */
    private final Map<String, String> pinned = new ConcurrentHashMap<>();

    /**
     * 旧版本按用户名固定的公钥。升级后首次使用时迁移到具体设备。
     */
    private final Map<String, String> legacy = new ConcurrentHashMap<>();

    private volatile String selfUsername;

    public TrustStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "trust"));
    }

    public TrustStore(Path directory) {
        this.directory = directory;
    }

    // ==================== 加载与保存 ====================

    /**
     * 加载某个用户的信任库。
     */
    public void load(String selfUsername, String password) throws IdentityException {
        this.selfUsername = selfUsername;
        pinned.clear();
        legacy.clear();
        Path file = fileOf(selfUsername);
        if (!Files.exists(file)) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            byte[] salt = null;
            byte[] nonce = null;
            byte[] data = null;
            for (String line : content.split("\n")) {
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx);
                String value = line.substring(idx + 1).trim();
                switch (key) {
                    case "salt":
                        salt = java.util.Base64.getDecoder().decode(value);
                        break;
                    case "nonce":
                        nonce = java.util.Base64.getDecoder().decode(value);
                        break;
                    case "data":
                        data = java.util.Base64.getDecoder().decode(value);
                        break;
                    default:
                        break;
                }
            }
            if (salt == null || nonce == null || data == null) {
                throw new IdentityException("信任库文件格式损坏");
            }
            byte[] plain = SecretBox.open(password, selfUsername + "|trust", salt, nonce, data);
            String body = new String(plain, StandardCharsets.UTF_8);
            parseBody(body);
            log.info("已加载 {} 条设备信任记录、{} 条旧版记录", pinned.size(), legacy.size());
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("读取信任库失败：" + e.getMessage());
        }
    }

    private void parseBody(String body) {
        for (String line : body.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(String.valueOf(FIELD_SEP), -1);
            if (parts.length >= 4 && RECORD_DEVICE.equals(parts[0])) {
                pinned.put(key(parts[1], parts[2]), parts[3]);
            } else if (parts.length >= 3 && RECORD_LEGACY.equals(parts[0])) {
                legacy.put(parts[1], parts[2]);
            } else if (line.indexOf(FIELD_SEP) < 0) {
                // 旧版本格式：username=publicKey
                int idx = line.indexOf('=');
                if (idx > 0) {
                    legacy.put(line.substring(0, idx), line.substring(idx + 1).trim());
                }
            }
        }
    }

    // ==================== 校验 ====================

    /**
     * 校验一台设备的身份公钥。首次见到该用户时会固定其公钥；
     * 已固定用户新增设备时返回 {@link TrustStatus#NEW_DEVICE}，不会自动固定。
     */
    public VerificationResult verify(String peer, String deviceId, String identityPublicKey,
        String password) throws IdentityException {
        String device = normalizeDevice(deviceId);
        String fingerprint = fingerprint(identityPublicKey);
        String existing = pinned.get(key(peer, device));
        if (existing != null) {
            return compare(peer, device, existing, identityPublicKey, fingerprint);
        }
        String legacyKey = legacy.get(peer);
        if (legacyKey != null) {
            VerificationResult result = compare(peer, device, legacyKey, identityPublicKey, fingerprint);
            if (result.isSendable()) {
                // 旧版只能确认「这个用户的某一台设备」，迁移到当前设备并移除旧记录
                pinned.put(key(peer, device), identityPublicKey);
                legacy.remove(peer);
                persist(password);
            }
            return result;
        }
        if (!hasPins(peer)) {
            pinned.put(key(peer, device), identityPublicKey);
            persist(password);
            log.info("首次固定用户 {} 设备 {} 的身份公钥，指纹 {}", peer, device, fingerprint);
            return new VerificationResult(TrustStatus.NEW, peer, device, null, fingerprint,
                identityPublicKey);
        }
        log.warn("用户 {} 出现未固定设备 {}，指纹 {}", peer, device, fingerprint);
        return new VerificationResult(TrustStatus.NEW_DEVICE, peer, device, null, fingerprint,
            identityPublicKey);
    }

    /**
     * 一次性校验某用户当前所有设备。
     *
     * <p>首次见到该用户时把整组设备一起固定；否则逐台判断，新增设备返回
     * {@link TrustStatus#NEW_DEVICE} 且不会被写入。调用方必须先验证所有设备的
     * 预密钥签名，再调用本方法，避免把伪造公钥固定下来。
     */
    public List<VerificationResult> verifyAll(String peer, Map<String, String> deviceKeys,
        String password) throws IdentityException {
        List<VerificationResult> results = new ArrayList<>();
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return results;
        }
        boolean firstContact = !hasPins(peer);
        boolean migrated = false;

        if (firstContact) {
            for (Map.Entry<String, String> entry : deviceKeys.entrySet()) {
                String device = normalizeDevice(entry.getKey());
                pinned.put(key(peer, device), entry.getValue());
                results.add(new VerificationResult(TrustStatus.NEW, peer, device, null,
                    fingerprint(entry.getValue()), entry.getValue()));
            }
            persist(password);
            log.info("首次固定用户 {} 的 {} 台设备", peer, results.size());
            return results;
        }

        for (Map.Entry<String, String> entry : deviceKeys.entrySet()) {
            String device = normalizeDevice(entry.getKey());
            String key = entry.getValue();
            String fp = fingerprint(key);
            String existing = pinned.get(key(peer, device));
            if (existing != null) {
                results.add(compare(peer, device, existing, key, fp));
                continue;
            }
            String legacyKey = legacy.get(peer);
            if (legacyKey != null && legacyKey.equals(key)) {
                pinned.put(key(peer, device), key);
                legacy.remove(peer);
                migrated = true;
                results.add(new VerificationResult(TrustStatus.TRUSTED, peer, device, fp, fp, key));
                continue;
            }
            if (legacyKey != null && !legacyKey.equals(key)) {
                results.add(new VerificationResult(TrustStatus.CHANGED, peer, device,
                    fingerprint(legacyKey), fp, key));
                continue;
            }
            results.add(new VerificationResult(TrustStatus.NEW_DEVICE, peer, device, null, fp, key));
        }
        if (migrated) {
            persist(password);
        }
        return results;
    }

    private VerificationResult compare(String peer, String device, String existing,
        String identityPublicKey, String fingerprint) {
        if (existing.equals(identityPublicKey)) {
            return new VerificationResult(TrustStatus.TRUSTED, peer, device,
                fingerprint(existing), fingerprint, identityPublicKey);
        }
        log.warn("用户 {} 设备 {} 的身份公钥发生变化！原指纹 {} 新指纹 {}", peer, device,
            fingerprint(existing), fingerprint);
        return new VerificationResult(TrustStatus.CHANGED, peer, device,
            fingerprint(existing), fingerprint, identityPublicKey);
    }

    /**
     * 用户核对指纹后显式接受一台设备的新公钥。
     */
    public void acceptDevice(String peer, String deviceId, String identityPublicKey, String password)
        throws IdentityException {
        acceptDevices(peer, java.util.Collections.singletonMap(deviceId, identityPublicKey), password);
    }

    /**
     * 用户核对指纹后显式接受一组设备的新公钥。
     */
    public void acceptDevices(String peer, Map<String, String> deviceKeys, String password)
        throws IdentityException {
        if (deviceKeys == null) {
            return;
        }
        for (Map.Entry<String, String> entry : deviceKeys.entrySet()) {
            pinned.put(key(peer, normalizeDevice(entry.getKey())), entry.getValue());
        }
        legacy.remove(peer);
        persist(password);
        log.warn("用户确认接受 {} 的 {} 台设备", peer, deviceKeys.size());
    }

    // ==================== 查询 ====================

    /**
     * 取得某台设备已固定的公钥，未固定返回 null。
     */
    public String getPinned(String peer, String deviceId) {
        return pinned.get(key(peer, normalizeDevice(deviceId)));
    }

    /**
     * 取得某个用户的全部已固定设备。
     */
    public Map<String, String> getPinnedDevices(String peer) {
        Map<String, String> result = new LinkedHashMap<>();
        String prefix = peer + String.valueOf(FIELD_SEP);
        for (Map.Entry<String, String> entry : pinned.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        // 兼容尚未迁移的旧记录：旧客户端默认设备是 default
        String legacyKey = legacy.get(peer);
        if (legacyKey != null && !result.containsKey(DEFAULT_DEVICE)) {
            result.put(DEFAULT_DEVICE, legacyKey);
        }
        return result;
    }

    public boolean isPinned(String peer, String deviceId) {
        return pinned.containsKey(key(peer, normalizeDevice(deviceId)));
    }

    public boolean hasPins(String peer) {
        String prefix = peer + String.valueOf(FIELD_SEP);
        for (String k : pinned.keySet()) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        return legacy.containsKey(peer);
    }

    public Map<String, String> allPinned() {
        return new LinkedHashMap<>(pinned);
    }

    /**
     * 用新口令重新加密信任库（改密时调用）。
     */
    public void changePassword(String newPassword) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("信任库未初始化");
        }
        persist(newPassword);
    }

    public void reset() {
        pinned.clear();
        legacy.clear();
        selfUsername = null;
    }

    // ==================== 持久化 ====================

    private void persist(String password) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("信任库未初始化");
        }
        try {
            StringBuilder sb = new StringBuilder(1024);
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(pinned).entrySet()) {
                int idx = entry.getKey().indexOf(FIELD_SEP);
                if (idx <= 0) {
                    continue;
                }
                sb.append(RECORD_DEVICE).append(FIELD_SEP)
                    .append(entry.getKey(), 0, idx).append(FIELD_SEP)
                    .append(entry.getKey().substring(idx + 1)).append(FIELD_SEP)
                    .append(entry.getValue()).append('\n');
            }
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(legacy).entrySet()) {
                sb.append(RECORD_LEGACY).append(FIELD_SEP)
                    .append(entry.getKey()).append(FIELD_SEP)
                    .append(entry.getValue()).append('\n');
            }
            SecretBox.Sealed sealed = SecretBox.seal(password, selfUsername + "|trust",
                sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder file = new StringBuilder(512);
            file.append("salt=").append(java.util.Base64.getEncoder().encodeToString(sealed.getSalt()))
                .append('\n');
            file.append("nonce=").append(java.util.Base64.getEncoder().encodeToString(sealed.getNonce()))
                .append('\n');
            file.append("data=").append(java.util.Base64.getEncoder().encodeToString(sealed.getCiphertext()))
                .append('\n');

            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "trust-", ".tmp");
            Files.write(temp, file.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(temp, new java.util.HashSet<>(java.util.Arrays.asList(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
            } catch (Exception ignored) {
                // 非 POSIX 文件系统，忽略
            }
            Files.move(temp, fileOf(selfUsername), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IdentityException("保存信任库失败：" + e.getMessage());
        }
    }

    private Path fileOf(String username) {
        String safe = username == null ? "unknown"
            : username.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
        return directory.resolve(safe + ".trust");
    }

    // ==================== 工具 ====================

    private static String key(String peer, String deviceId) {
        return peer + FIELD_SEP + normalizeDevice(deviceId);
    }

    private static String normalizeDevice(String deviceId) {
        return deviceId == null || deviceId.isEmpty() ? DEFAULT_DEVICE : deviceId;
    }

    private static String fingerprint(String identityPublicKey) {
        return Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(identityPublicKey));
    }

    /**
     * 展示用短指纹：每 4 位一组，便于人工比对。
     */
    public static String shortFingerprint(String fullFingerprint) {
        if (fullFingerprint == null) {
            return "";
        }
        String normalized = fullFingerprint.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        String head = normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
        StringBuilder sb = new StringBuilder(head.length() + head.length() / 4);
        for (int i = 0; i < head.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(head.charAt(i));
        }
        return sb.toString();
    }
}
