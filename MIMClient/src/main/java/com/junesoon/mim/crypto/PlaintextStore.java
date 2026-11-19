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
    private static final String FIELD_ITERATIONS = "iterations=";
    private static final String SENT_PREFIX = "sent|";
    private static final String TOKEN_PREFIX = "token|";
    private static final String TAG_PREFIX = "tags|";
    private static final String BLOCK_PREFIX = "blocked|";

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
            int iterations = readIterations(content);
            byte[] plain = SecretBox.open(password, aad(username),
                Base64.getDecoder().decode(salt), Base64.getDecoder().decode(nonce),
                Base64.getDecoder().decode(data), iterations);
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

    /**
     * 本机发出的明文。sealed 消息在服务端没有明文 from_user，重新打开聊天时
     * 不能靠棘轮解自己的密文，只能用这份按 clientMsgId 索引的本地副本。
     */
    public String getSent(String peer, String clientMsgId) {
        if (peer == null || clientMsgId == null) {
            return null;
        }
        return texts.get(SENT_PREFIX + peer + "|" + clientMsgId);
    }

    public void putSent(String peer, String clientMsgId, String plaintext) {
        if (peer == null) {
            return;
        }
        put(SENT_PREFIX + peer + "|" + clientMsgId, plaintext);
    }

    /**
     * 取发送时保存的一次性撤回凭证。凭证与消息一起加密落盘，
     * 服务端只有哈希，丢失凭证就只能走旧版兼容撤回路径。
     */
    public String getRecallToken(String peer, String clientMsgId) {
        if (peer == null || clientMsgId == null) {
            return null;
        }
        return texts.get(TOKEN_PREFIX + peer + "|" + clientMsgId);
    }

    public void putRecallToken(String peer, String clientMsgId, String token) {
        if (peer == null || clientMsgId == null || token == null) {
            return;
        }
        put(TOKEN_PREFIX + peer + "|" + clientMsgId, token);
    }

    /**
     * 本机持有的、与该对端的会话标签（可能多个：对端每台设备各自生成过）。
     *
     * <p>标签是双方共享的不透明值，服务端按它查历史，不再需要
     * 「从用户名推导的盲引用」。标签随首条消息的加密信封送达。
     */
    public java.util.List<String> getConversationTags(String peer) {
        if (peer == null) {
            return java.util.Collections.emptyList();
        }
        String value = texts.get(TAG_PREFIX + peer);
        if (value == null || value.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return new java.util.ArrayList<>(Arrays.asList(value.split(",")));
    }

    /**
     * 记录一个会话标签。上限 20 个，超出的标签不会影响消息收发，
     * 只是历史查询可能少一条来源。
     */
    /**
     * 是否已屏蔽该联系人。屏蔽是本地行为：匿名消息里服务端不知道发送者，
     * 只能在客户端按发送者证书做拦截。
     */
    public boolean isBlocked(String peer) {
        return peer != null && "1".equals(texts.get(BLOCK_PREFIX + peer));
    }

    public void block(String peer) {
        if (peer == null || peer.isEmpty()) {
            return;
        }
        texts.put(BLOCK_PREFIX + peer, "1");
        persistQuietly("屏蔽联系人状态保存失败");
    }

    public void unblock(String peer) {
        if (peer == null || peer.isEmpty()) {
            return;
        }
        texts.remove(BLOCK_PREFIX + peer);
        persistQuietly("解除屏蔽状态保存失败");
    }

    public void addConversationTag(String peer, String tag) {
        if (peer == null || tag == null || tag.isEmpty()) {
            return;
        }
        java.util.List<String> tags =
            new java.util.ArrayList<>(getConversationTags(peer));
        if (tags.contains(tag) || tags.size() >= 20) {
            return;
        }
        tags.add(tag);
        texts.put(TAG_PREFIX + peer, String.join(",", tags));
        persistQuietly("会话标签保存失败");
    }

    /**
     * 立即落盘；未解锁（无用户名/口令）时只留在内存。
     */
    private void persistQuietly(String failureMessage) {
        if (username == null || password == null) {
            return;
        }
        try {
            save();
        } catch (Exception e) {
            log.warn(failureMessage + "：{}", e.getMessage());
        }
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
        file.append(FIELD_ITERATIONS).append(sealed.getIterations()).append('\n');

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

    private int readIterations(String content) {
        try {
            return Integer.parseInt(field(content, FIELD_ITERATIONS));
        } catch (Exception e) {
            return SecretBox.LEGACY_ITERATIONS;
        }
    }

    private void restrict(Path file) {
        FilePermissions.ownerOnly(file);
    }
}
