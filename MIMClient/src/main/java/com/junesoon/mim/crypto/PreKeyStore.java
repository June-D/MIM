package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 预密钥本地库：保存自己的签名预密钥与一次性预密钥的私钥。
 *
 * <p>这些私钥用于响应别人发起的会话，必须加密落盘（与身份私钥同级别保护）。
 *
 * <p>一次性预密钥的生命周期特殊：<b>上传到服务端后本地必须保留私钥</b>，
 * 直到有人用它建立会话。一旦被使用，对应私钥就应当删除（用完即弃，
 * 这正是 OPK 提供前向安全性的原因）。
 */
@Slf4j
public class PreKeyStore {

    private static final int ONE_TIME_PREKEY_BATCH = 50;

    /**
     * 缺省设备标识，兼容单设备时期的本地文件。
     */
    public static final String DEFAULT_DEVICE_ID = "default";

    /**
     * 本地保存的签名预密钥。
     */
    public static final class LocalSignedPreKey {
        private final int preKeyId;
        private final PrivateKey privateKey;
        private final String publicKeyBase64;
        private final String signatureBase64;
        private final long createdAt;

        public LocalSignedPreKey(int preKeyId, PrivateKey privateKey, String publicKeyBase64,
            String signatureBase64) {
            this(preKeyId, privateKey, publicKeyBase64, signatureBase64, 0L);
        }

        public LocalSignedPreKey(int preKeyId, PrivateKey privateKey, String publicKeyBase64,
            String signatureBase64, long createdAt) {
            this.preKeyId = preKeyId;
            this.privateKey = privateKey;
            this.publicKeyBase64 = publicKeyBase64;
            this.signatureBase64 = signatureBase64;
            this.createdAt = createdAt;
        }

        public int getPreKeyId() {
            return preKeyId;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        public String getPublicKeyBase64() {
            return publicKeyBase64;
        }

        public String getSignatureBase64() {
            return signatureBase64;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * 本地保存的一次性预密钥。
     */
    public static final class LocalOneTimePreKey {
        private final int preKeyId;
        private final PrivateKey privateKey;
        private final String publicKeyBase64;

        public LocalOneTimePreKey(int preKeyId, PrivateKey privateKey, String publicKeyBase64) {
            this.preKeyId = preKeyId;
            this.privateKey = privateKey;
            this.publicKeyBase64 = publicKeyBase64;
        }

        public int getPreKeyId() {
            return preKeyId;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        public String getPublicKeyBase64() {
            return publicKeyBase64;
        }
    }

    private final Path directory;

    /**
     * 内存中的一次性预密钥私钥。key 为 preKeyId。
     */
    private final Map<Integer, LocalOneTimePreKey> oneTimeKeys = new ConcurrentHashMap<>();

    /**
     * 保留最近几把签名预密钥。轮换后旧私钥不能立即删除，
     * 否则轮换前发出的首条握手消息会永久解不开。
     */
    private final Map<Integer, LocalSignedPreKey> signedPreKeys = new ConcurrentHashMap<>();

    /**
     * 本地最多保留的签名预密钥数量（当前 + 2 把旧钥）。
     */
    private static final int MAX_SIGNED_PREKEYS = 3;

    private volatile LocalSignedPreKey signedPreKey;

    /**
     * 下一次生成一次性预密钥时使用的起始编号。
     */
    private volatile int nextOneTimeKeyId = 1;

    public PreKeyStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "prekeys"));
    }

    public PreKeyStore(Path directory) {
        this.directory = directory;
    }

    // ==================== 生成 ====================

    /**
     * 生成并签名一把新的签名预密钥。
     *
     * @param identityPrivate 身份私钥，用于生成 ECDSA 签名
     * @param preKeyId        编号
     */
    public LocalSignedPreKey generateSignedPreKey(PrivateKey identityPrivate, int preKeyId) {
        java.security.KeyPair pair = DhKeyUtil.generateKeyPair();
        String publicBase64 = DhKeyUtil.encodePublicKey(pair.getPublic());
        byte[] payload = DhKeyUtil.signedPreKeyPayload(preKeyId,
            Base64.getDecoder().decode(publicBase64));
        String signature = DhKeyUtil.sign(identityPrivate, payload);
        return new LocalSignedPreKey(preKeyId, pair.getPrivate(), publicBase64, signature,
            System.currentTimeMillis());
    }

    /**
     * 批量生成一次性预密钥。
     */
    public java.util.List<LocalOneTimePreKey> generateOneTimePreKeys(int count) {
        java.util.List<LocalOneTimePreKey> generated = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = nextOneTimeKeyId++;
            java.security.KeyPair pair = DhKeyUtil.generateKeyPair();
            generated.add(new LocalOneTimePreKey(id, pair.getPrivate(),
                DhKeyUtil.encodePublicKey(pair.getPublic())));
        }
        return generated;
    }

    // ==================== 存取 ====================

    public void setSignedPreKey(LocalSignedPreKey key) {
        this.signedPreKey = key;
        if (key != null) {
            signedPreKeys.put(key.getPreKeyId(), key);
            pruneSignedPreKeys();
        }
    }

    /**
     * 按编号找签名预密钥。用于处理轮换前发出的握手消息。
     */
    public LocalSignedPreKey findSignedPreKey(int preKeyId) {
        LocalSignedPreKey key = signedPreKeys.get(preKeyId);
        if (key != null) {
            return key;
        }
        LocalSignedPreKey current = signedPreKey;
        return current != null && current.getPreKeyId() == preKeyId ? current : null;
    }

    private void pruneSignedPreKeys() {
        if (signedPreKeys.size() <= MAX_SIGNED_PREKEYS) {
            return;
        }
        java.util.List<Integer> ids = new java.util.ArrayList<>(signedPreKeys.keySet());
        java.util.Collections.sort(ids);
        while (ids.size() > MAX_SIGNED_PREKEYS) {
            signedPreKeys.remove(ids.remove(0));
        }
    }

    public LocalSignedPreKey getSignedPreKey() {
        return signedPreKey;
    }

    public void addOneTimePreKeys(java.util.List<LocalOneTimePreKey> keys) {
        for (LocalOneTimePreKey key : keys) {
            oneTimeKeys.put(key.getPreKeyId(), key);
        }
    }

    /**
     * 取出一次性预密钥私钥，但不删除。解密成功后再 {@link #consumeOneTimePreKey(int)}。
     */
    public LocalOneTimePreKey peekOneTimePreKey(int preKeyId) {
        return oneTimeKeys.get(preKeyId);
    }

    /**
     * 取出被使用的一次性预密钥私钥，并立即从内存删除（用完即弃）。
     */
    public LocalOneTimePreKey consumeOneTimePreKey(int preKeyId) {
        return oneTimeKeys.remove(preKeyId);
    }

    public boolean hasOneTimePreKey(int preKeyId) {
        return oneTimeKeys.containsKey(preKeyId);
    }

    public int oneTimeKeyCount() {
        return oneTimeKeys.size();
    }

    public int nextOneTimeKeyIdHint() {
        return nextOneTimeKeyId;
    }

    public void setNextOneTimeKeyId(int value) {
        this.nextOneTimeKeyId = value;
    }

    public void clear() {
        signedPreKey = null;
        signedPreKeys.clear();
        oneTimeKeys.clear();
    }

    /**
     * 用新口令重新加密预密钥文件（改密时调用）。
     */
    public void changePassword(String selfUsername, String deviceId, String oldPassword,
        String newPassword) throws IdentityException {
        if (!load(selfUsername, deviceId, oldPassword)) {
            return;
        }
        save(selfUsername, deviceId, newPassword);
    }

    // ==================== 持久化 ====================

    /**
     * 把预密钥私钥加密落盘。
     *
     * <p>格式：行式 key=value，值均 Base64，整体经 AES-GCM 加密后写入。
     */
    public void save(String selfUsername, String password) throws IdentityException {
        save(selfUsername, DEFAULT_DEVICE_ID, password);
    }

    /**
     * 按设备保存预密钥私钥。
     */
    public void save(String selfUsername, String deviceId, String password)
        throws IdentityException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("nextOneTimeKeyId=").append(nextOneTimeKeyId).append('\n');
        LocalSignedPreKey signed = signedPreKey;
        if (signed != null) {
            sb.append("spkId=").append(signed.getPreKeyId()).append('\n');
            sb.append("spkCreatedAt=").append(signed.getCreatedAt()).append('\n');
            sb.append("spkPublic=").append(signed.getPublicKeyBase64()).append('\n');
            sb.append("spkSignature=").append(signed.getSignatureBase64()).append('\n');
            sb.append("spkPrivate=")
                .append(Base64.getEncoder().encodeToString(signed.getPrivateKey().getEncoded()))
                .append('\n');
        }
        // 旧签名预密钥只用于解开轮换前发出的握手，单独保存
        for (LocalSignedPreKey old : signedPreKeys.values()) {
            if (signed != null && old.getPreKeyId() == signed.getPreKeyId()) {
                continue;
            }
            sb.append("oldSpk=").append(old.getPreKeyId()).append(',')
                .append(old.getCreatedAt()).append(',')
                .append(Base64.getEncoder().encodeToString(old.getPrivateKey().getEncoded()))
                .append(',').append(old.getPublicKeyBase64())
                .append(',').append(old.getSignatureBase64()).append('\n');
        }
        for (LocalOneTimePreKey key : oneTimeKeys.values()) {
            sb.append("opk=").append(key.getPreKeyId()).append(',')
                .append(Base64.getEncoder().encodeToString(key.getPrivateKey().getEncoded())).append(',')
                .append(key.getPublicKeyBase64()).append('\n');
        }
        writeSealed(selfUsername, deviceId, password, sb.toString());
    }

    /**
     * 从磁盘加载。文件不存在返回 false。
     */
    public boolean load(String selfUsername, String password) throws IdentityException {
        return load(selfUsername, DEFAULT_DEVICE_ID, password);
    }

    /**
     * 按设备加载本地预密钥私钥。
     */
    public boolean load(String selfUsername, String deviceId, String password)
        throws IdentityException {
        Path file = fileOf(selfUsername, deviceId);
        if (!Files.exists(file)) {
            return false;
        }
        String content = readSealed(selfUsername, deviceId, password, file);
        oneTimeKeys.clear();
        signedPreKeys.clear();
        signedPreKey = null;
        int spkId = 0;
        long spkCreatedAt = 0L;
        String spkPublic = null;
        String spkSignature = null;
        byte[] spkPrivate = null;
        Map<Integer, LocalSignedPreKey> loadedOld = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx);
            String value = line.substring(idx + 1).trim();
            switch (key) {
                case "nextOneTimeKeyId":
                    nextOneTimeKeyId = parseInt(value, 1);
                    break;
                case "spkId":
                    spkId = parseInt(value, 0);
                    break;
                case "spkCreatedAt":
                    spkCreatedAt = parseLong(value, 0L);
                    break;
                case "spkPublic":
                    spkPublic = value;
                    break;
                case "spkSignature":
                    spkSignature = value;
                    break;
                case "spkPrivate":
                    spkPrivate = Base64.getDecoder().decode(value);
                    break;
                case "oldSpk":
                    String[] old = value.split(",", 5);
                    if (old.length == 5) {
                        int id = parseInt(old[0], 0);
                        long createdAt = parseLong(old[1], 0L);
                        byte[] pk = Base64.getDecoder().decode(old[2]);
                        loadedOld.put(id, new LocalSignedPreKey(id,
                            DhKeyUtil.decodePrivateKey(pk), old[3], old[4], createdAt));
                    }
                    break;
                case "opk":
                    String[] parts = value.split(",");
                    if (parts.length == 3) {
                        int id = parseInt(parts[0], 0);
                        byte[] pk = Base64.getDecoder().decode(parts[1]);
                        oneTimeKeys.put(id, new LocalOneTimePreKey(id,
                            DhKeyUtil.decodePrivateKey(pk), parts[2]));
                    }
                    break;
                default:
                    break;
            }
        }
        if (spkPrivate != null && spkPublic != null && spkSignature != null) {
            signedPreKey = new LocalSignedPreKey(spkId, DhKeyUtil.decodePrivateKey(spkPrivate),
                spkPublic, spkSignature, spkCreatedAt);
            signedPreKeys.put(signedPreKey.getPreKeyId(), signedPreKey);
        }
        for (LocalSignedPreKey old : loadedOld.values()) {
            signedPreKeys.put(old.getPreKeyId(), old);
        }
        pruneSignedPreKeys();
        log.info("已加载本地预密钥：签名预密钥={}（保留 {} 把） 一次性预密钥={}",
            signedPreKey == null ? "无" : signedPreKey.getPreKeyId(),
            signedPreKeys.size(), oneTimeKeys.size());
        return true;
    }

    private Path fileOf(String username, String deviceId) {
        String safe = sanitize(username);
        String device = sanitize(deviceId);
        return directory.resolve(safe + "__" + device + ".prekeys");
    }

    private String sanitize(String value) {
        return value == null ? "unknown"
            : value.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
    }

    private String aad(String username, String deviceId) {
        String device = deviceId == null || deviceId.isEmpty() ? DEFAULT_DEVICE_ID : deviceId;
        return username + "|" + device;
    }

    private void writeSealed(String username, String deviceId, String password, String plaintext)
        throws IdentityException {
        try {
            SecretBox.Sealed sealed = SecretBox.seal(password, aad(username, deviceId) + "|prekeys",
                plaintext.getBytes(StandardCharsets.UTF_8));
            Map<String, String> fields = new LinkedHashMap<>(5);
            fields.put("salt", Base64.getEncoder().encodeToString(sealed.getSalt()));
            fields.put("nonce", Base64.getEncoder().encodeToString(sealed.getNonce()));
            fields.put("data", Base64.getEncoder().encodeToString(sealed.getCiphertext()));
            fields.put("iterations", String.valueOf(sealed.getIterations()));
            StringBuilder sb = new StringBuilder(512);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "prekeys-", ".tmp");
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8));
            restrictPermissions(temp);
            Files.move(temp, fileOf(username, deviceId), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IdentityException("保存预密钥失败：" + e.getMessage());
        }
    }

    private String readSealed(String username, String deviceId, String password, Path file)
        throws IdentityException {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            byte[] salt = null;
            byte[] nonce = null;
            byte[] data = null;
            int iterations = SecretBox.LEGACY_ITERATIONS;
            for (String line : content.split("\n")) {
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx);
                String value = line.substring(idx + 1).trim();
                switch (key) {
                    case "salt":
                        salt = Base64.getDecoder().decode(value);
                        break;
                    case "nonce":
                        nonce = Base64.getDecoder().decode(value);
                        break;
                    case "data":
                        data = Base64.getDecoder().decode(value);
                        break;
                    case "iterations":
                        iterations = Integer.parseInt(value);
                        break;
                    default:
                        break;
                }
            }
            if (salt == null || nonce == null || data == null) {
                throw new IdentityException("预密钥文件格式损坏");
            }
            byte[] plain = SecretBox.open(password, aad(username, deviceId) + "|prekeys",
                salt, nonce, data, iterations);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("读取预密钥失败：" + e.getMessage());
        }
    }

    private void restrictPermissions(Path file) {
        FilePermissions.ownerOnly(file);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int batchSize() {
        return ONE_TIME_PREKEY_BATCH;
    }
}
