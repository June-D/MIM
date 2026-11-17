package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * TOFU（Trust On First Use）信任库。
 *
 * <p>解决的问题：身份公钥由服务端目录分发，服务端若被攻破或本身就是恶意的，
 * 可以把 alice 的公钥换成攻击者的公钥，从而中间人解密所有消息。
 * 端到端加密里服务端本就不该是可信的。
 *
 * <p>做法：第一次见到某用户的身份公钥时把它「固定」下来（首次使用即信任），
 * 此后每次使用都对比。一旦发现变化：
 * <ul>
 *   <li><b>不静默接受</b> —— 那等于没有防护；</li>
 *   <li>标记为 {@link TrustStatus#CHANGED}，阻断发送，要求用户线下核对指纹后
 *       显式确认，才更新固定值。</li>
 * </ul>
 *
 * <p>这跟 SSH 的 known_hosts 是同一个思路。局限是「首次」本身仍可能被中间人劫持，
 * 所以要配合界面上展示的指纹让用户线下核对（安全码比对）。
 */
@Slf4j
public class TrustStore {

    /**
     * 信任状态。
     */
    public enum TrustStatus {
        /** 首次见到，已固定 */
        NEW,
        /** 与固定值一致 */
        TRUSTED,
        /** 与固定值不一致，必须人工确认 */
        CHANGED
    }

    /**
     * 验证结果。
     */
    public static final class VerificationResult {
        private final TrustStatus status;
        private final String previousFingerprint;
        private final String currentFingerprint;

        VerificationResult(TrustStatus status, String previousFingerprint, String currentFingerprint) {
            this.status = status;
            this.previousFingerprint = previousFingerprint;
            this.currentFingerprint = currentFingerprint;
        }

        public TrustStatus getStatus() {
            return status;
        }

        public String getPreviousFingerprint() {
            return previousFingerprint;
        }

        public String getCurrentFingerprint() {
            return currentFingerprint;
        }

        public boolean isSendable() {
            return status != TrustStatus.CHANGED;
        }

        /**
         * 给用户看的说明。
         */
        public String describe() {
            switch (status) {
                case NEW:
                    return "首次与对方建立加密身份，已固定其公钥指纹 " + shortFingerprint(currentFingerprint);
                case TRUSTED:
                    return "对方公钥指纹与本地记录一致";
                default:
                    return "⚠ 对方公钥已变更！\n原指纹：" + shortFingerprint(previousFingerprint)
                        + "\n新指纹：" + shortFingerprint(currentFingerprint)
                        + "\n这可能是对方换了设备，也可能是中间人攻击。"
                        + "请通过其他可信渠道与对方核对指纹后再确认。";
            }
        }
    }

    private final Path directory;

    /**
     * 内存缓存：username -> 已固定的身份公钥 Base64。
     */
    private final Map<String, String> pinned = new ConcurrentHashMap<>();

    private volatile String selfUsername;

    public TrustStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "trust"));
    }

    public TrustStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 加载某个用户的信任库。
     */
    public void load(String selfUsername, String password) throws IdentityException {
        this.selfUsername = selfUsername;
        pinned.clear();
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
            for (String line : body.split("\n")) {
                int idx = line.indexOf('=');
                if (idx > 0) {
                    pinned.put(line.substring(0, idx), line.substring(idx + 1).trim());
                }
            }
            log.info("已加载 {} 条信任记录", pinned.size());
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("读取信任库失败：" + e.getMessage());
        }
    }

    /**
     * 校验对方公钥。
     *
     * <p>首次见到会立即固定并持久化；与已固定的不一致则返回 {@link TrustStatus#CHANGED}。
     */
    public VerificationResult verify(String peer, String identityPublicKey, String password)
        throws IdentityException {
        String fingerprint = Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(identityPublicKey));
        String existing = pinned.get(peer);
        if (existing == null) {
            pinned.put(peer, identityPublicKey);
            persist(password);
            log.info("首次固定用户 {} 的身份公钥，指纹 {}", peer, fingerprint);
            return new VerificationResult(TrustStatus.NEW, null, fingerprint);
        }
        String existingFingerprint = Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(existing));
        if (existing.equals(identityPublicKey)) {
            return new VerificationResult(TrustStatus.TRUSTED, existingFingerprint, fingerprint);
        }
        log.warn("用户 {} 的身份公钥发生变化！原指纹 {} 新指纹 {}", peer, existingFingerprint,
            fingerprint);
        return new VerificationResult(TrustStatus.CHANGED, existingFingerprint, fingerprint);
    }

    /**
     * 用户线下核对指纹后显式确认变更，覆盖固定值。
     */
    public void acceptChange(String peer, String identityPublicKey, String password)
        throws IdentityException {
        pinned.put(peer, identityPublicKey);
        persist(password);
        log.warn("用户确认接受 {} 的新身份公钥", peer);
    }

    /**
     * 取得已固定的公钥，未固定返回 null。
     */
    public String getPinned(String peer) {
        return pinned.get(peer);
    }

    public boolean isPinned(String peer) {
        return pinned.containsKey(peer);
    }

    public Map<String, String> allPinned() {
        return new LinkedHashMap<>(pinned);
    }

    public void reset() {
        pinned.clear();
        selfUsername = null;
    }

    private void persist(String password) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("信任库未初始化");
        }
        try {
            StringBuilder sb = new StringBuilder(1024);
            for (Map.Entry<String, String> entry : pinned.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
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
