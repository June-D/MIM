package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 本账号「自己的设备」信任库。
 *
 * <p>跨设备历史同步会把聊天明文重新加密给另一台设备。如果只凭服务端给出的
 * 设备列表就转发，被攻破的服务端或盗号者只要注册一台设备，就能拿走全部历史。
 * 因此这里维护一份<b>只由本机用户批准</b>的设备清单：
 * <ul>
 *   <li>当前设备在登录时自动信任（密钥就在本机）；</li>
 *   <li>任何其它设备都必须由用户在看到设备标识与指纹后显式确认；</li>
 *   <li>已信任设备的公钥发生变化时按不可信处理，重新确认。</li>
 * </ul>
 *
 * <p>与对端 {@link TrustStore} 的区别：这里<b>绝不自动固定</b>新设备，
 * 因为「本账号新增设备」正是攻击者最想要的持久化入口。
 */
@Slf4j
public class OwnDeviceTrust {

    private static final char FIELD_SEP = '\t';

    private final Path directory;

    /**
     * deviceId -> Base64(identityPublicKey)
     */
    private final Map<String, String> pinned = new ConcurrentHashMap<>();

    private volatile String selfUsername;

    public OwnDeviceTrust() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "own-devices"));
    }

    public OwnDeviceTrust(Path directory) {
        this.directory = directory;
    }

    /**
     * 加载当前账号的本机设备信任清单。
     *
     * <p>文件损坏或口令不匹配时按「空清单」处理并告警：此时只会信任当前设备，
     * 不会把历史转发给任何未确认设备，属于安全失败。
     */
    public void load(String selfUsername, String password) {
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
                log.warn("本机设备信任文件格式损坏，按空清单处理");
                return;
            }
            byte[] plain = SecretBox.open(password, aad(selfUsername), salt, nonce, data);
            String body = new String(plain, StandardCharsets.UTF_8);
            for (String line : body.split("\n")) {
                int idx = line.indexOf(FIELD_SEP);
                if (idx > 0 && idx < line.length() - 1) {
                    pinned.put(line.substring(0, idx), line.substring(idx + 1).trim());
                }
            }
            log.info("已加载 {} 台已授权本机设备", pinned.size());
        } catch (Exception e) {
            log.warn("读取本机设备信任清单失败，按空清单处理：{}", e.getMessage());
            pinned.clear();
        }
    }

    /**
     * 当前设备登录时自动信任自己。身份密钥就在本机，无需用户再确认。
     */
    public void trustCurrent(String deviceId, String identityPublicKey, String password)
        throws IdentityException {
        if (deviceId == null || identityPublicKey == null) {
            return;
        }
        String existing = pinned.get(deviceId);
        if (identityPublicKey.equals(existing)) {
            return;
        }
        if (existing != null) {
            log.warn("本机设备 {} 的身份公钥发生变化，按当前私钥更新信任记录", deviceId);
        }
        pinned.put(deviceId, identityPublicKey);
        persist(password);
    }

    /**
     * 用户确认后信任一台设备。
     */
    public void trustDevice(String deviceId, String identityPublicKey, String password)
        throws IdentityException {
        if (deviceId == null || identityPublicKey == null) {
            return;
        }
        pinned.put(deviceId, identityPublicKey);
        persist(password);
        log.warn("用户确认信任本账号设备 {}，指纹 {}", deviceId,
            TrustStore.shortFingerprint(Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(identityPublicKey))));
    }

    public String getPinned(String deviceId) {
        return deviceId == null ? null : pinned.get(deviceId);
    }

    public boolean isTrusted(String deviceId, String identityPublicKey) {
        if (deviceId == null || identityPublicKey == null) {
            return false;
        }
        return identityPublicKey.equals(pinned.get(deviceId));
    }

    public Map<String, String> getPinnedDevices() {
        return new LinkedHashMap<>(pinned);
    }

    /**
     * 用新口令重新加密本机设备信任清单（改密时调用）。
     */
    public void changePassword(String newPassword) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("本机设备信任清单未初始化");
        }
        persist(newPassword);
    }

    public void reset() {
        pinned.clear();
        selfUsername = null;
    }

    private void persist(String password) throws IdentityException {
        if (selfUsername == null) {
            throw new IdentityException("本机设备信任清单未初始化");
        }
        try {
            StringBuilder body = new StringBuilder(512);
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(pinned).entrySet()) {
                body.append(entry.getKey()).append(FIELD_SEP).append(entry.getValue()).append('\n');
            }
            SecretBox.Sealed sealed = SecretBox.seal(password, aad(selfUsername),
                body.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder file = new StringBuilder(512);
            file.append("salt=").append(java.util.Base64.getEncoder().encodeToString(sealed.getSalt()))
                .append('\n');
            file.append("nonce=").append(java.util.Base64.getEncoder().encodeToString(sealed.getNonce()))
                .append('\n');
            file.append("data=").append(java.util.Base64.getEncoder().encodeToString(sealed.getCiphertext()))
                .append('\n');

            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "own-devices-", ".tmp");
            Files.write(temp, file.toString().getBytes(StandardCharsets.UTF_8));
            restrict(temp);
            Files.move(temp, fileOf(selfUsername), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IdentityException("保存本机设备信任清单失败：" + e.getMessage());
        }
    }

    private Path fileOf(String username) {
        String safe = username == null ? "unknown"
            : username.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
        return directory.resolve(safe + ".trusted");
    }

    private String aad(String username) {
        return username + "|own-devices";
    }

    private void restrict(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
            // 非 POSIX 文件系统，忽略
        }
    }
}
