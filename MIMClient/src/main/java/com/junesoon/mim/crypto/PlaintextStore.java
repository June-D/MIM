package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 本机已经见过的明文。
 *
 * <p>双棘轮的消息密钥用完即弃，同一条密文不能再解第二次。聊天记录每次打开都会
 * 把服务器上的密文拉下来，所以必须在第一次解开（或自己发出）时把明文留在本地。
 */
@Slf4j
public final class PlaintextStore {

    private static final String FIELD_SALT = "salt=";
    private static final String FIELD_NONCE = "nonce=";
    private static final String FIELD_DATA = "data=";

    private final Path directory;
    private final Map<String, String> texts = new LinkedHashMap<>();

    private String username;
    private String password;

    public PlaintextStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "plain"));
    }

    public PlaintextStore(Path directory) {
        this.directory = directory;
    }

    public void load(String username, String password) {
        this.username = username;
        this.password = password;
        texts.clear();
        Path file = fileOf(username);
        if (!Files.exists(file)) {
            // 兼容旧版文件名：旧实现把非 ASCII 字符全替换成下划线，
            // 中文用户名会互相碰撞。迁移到哈希文件名后互不影响。
            Path legacy = legacyFileOf(username);
            if (!Files.exists(legacy)) {
                return;
            }
            file = legacy;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String salt = field(content, FIELD_SALT);
            String nonce = field(content, FIELD_NONCE);
            String data = field(content, FIELD_DATA);
            byte[] plain = SecretBox.open(password, aad(username),
                Base64.getDecoder().decode(salt), Base64.getDecoder().decode(nonce),
                Base64.getDecoder().decode(data));
            String body = new String(plain, StandardCharsets.UTF_8);
            Hkdf.wipe(plain);
            for (String line : body.split("\n")) {
                int idx = line.indexOf('\t');
                if (idx <= 0) {
                    continue;
                }
                String id = line.substring(0, idx);
                String text = new String(Base64.getDecoder().decode(line.substring(idx + 1)),
                    StandardCharsets.UTF_8);
                texts.put(id, text);
            }
            if (!file.equals(fileOf(username))) {
                // 迁移到新文件名；旧文件保留，避免多账号共用时误删
                save();
            }
        } catch (Exception e) {
            log.warn("读取本地明文记录失败，已发出的历史消息可能无法显示：{}", e.getMessage());
        }
    }

    public void clear() {
        texts.clear();
        username = null;
        password = null;
    }

    public String get(String clientMsgId) {
        if (clientMsgId == null) {
            return null;
        }
        return texts.get(clientMsgId);
    }

    public void put(String clientMsgId, String plaintext) {
        if (clientMsgId == null || plaintext == null || texts.containsKey(clientMsgId)) {
            return;
        }
        texts.put(clientMsgId, plaintext);
        if (username == null || password == null) {
            return;
        }
        try {
            save();
        } catch (Exception e) {
            log.warn("保存本地明文记录失败：{}", e.getMessage());
        }
    }

    /**
     * 用新口令重新加密本地明文（改密时调用）。
     */
    public void changePassword(String newPassword) throws IdentityException {
        if (username == null) {
            return;
        }
        this.password = newPassword;
        try {
            save();
        } catch (Exception e) {
            throw new IdentityException("重新加密本地明文失败：" + e.getMessage());
        }
    }

    private void save() throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            body.append(entry.getKey()).append('\t')
                .append(Base64.getEncoder().encodeToString(
                    entry.getValue().getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        }
        SecretBox.Sealed sealed = SecretBox.seal(password, aad(username),
            body.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder file = new StringBuilder();
        file.append(FIELD_SALT).append(Base64.getEncoder().encodeToString(sealed.getSalt())).append('\n');
        file.append(FIELD_NONCE).append(Base64.getEncoder().encodeToString(sealed.getNonce())).append('\n');
        file.append(FIELD_DATA).append(Base64.getEncoder().encodeToString(sealed.getCiphertext())).append('\n');

        Files.createDirectories(directory);
        Path target = fileOf(username);
        Path temp = Files.createTempFile(directory, "plain-", ".tmp");
        try {
            Files.write(temp, file.toString().getBytes(StandardCharsets.UTF_8));
            restrict(temp);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path fileOf(String name) {
        // 用 SHA-256 而不是「保留 ASCII」：中文用户名在旧方案里会全部变成下划线，
        // 同一台机器上的两个中文账号会共用同一个文件。
        String digest = Hkdf.sha256Hex(
            (name == null ? "unknown" : name).getBytes(StandardCharsets.UTF_8));
        return directory.resolve(digest.toLowerCase() + ".box");
    }

    private Path legacyFileOf(String name) {
        String safe = name == null ? "unknown" : name.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return directory.resolve(safe + ".box");
    }

    private String aad(String name) {
        return name + "|plain";
    }

    private String field(String content, String prefix) throws IdentityException {
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new IdentityException("明文记录缺少字段：" + prefix);
    }

    private void restrict(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
            // 非 POSIX 文件系统上忽略
        }
    }
}
