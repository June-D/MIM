package com.junesoon.mim.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话状态的加密持久化（多设备）。
 *
 * <p>双棘轮状态一旦丢失或回滚，会导致密钥链不同步、后续消息永久无法解密。
 * 因此这里做三件事：
 * <ol>
 *   <li><b>加密落盘</b>：状态含私钥与链密钥，必须用登录口令派生密钥加密；</li>
 *   <li><b>原子写</b>：先写临时文件再 rename，避免断电留下半个文件；</li>
 *   <li><b>单会话单文件</b>：只更新当前会话，不会整体覆盖全部会话。</li>
 * </ol>
 *
 * <p>文件名格式 {@code <自己>__<对端>__<对端设备>.session}。
 * 同一对端在不同设备上是<b>独立会话</b>，必须分开存。
 */
@Slf4j
public class SessionStore {

    /**
     * 缺省设备标识，兼容单设备时期的本地文件。
     */
    public static final String DEFAULT_DEVICE_ID = "default";

    private static final String SEP = "__";
    private static final String SUFFIX = ".session";
    private static final String INBOUND_SUFFIX = ".inbound.session";

    private static final String FIELD_PEER = "peer=";
    private static final String FIELD_DEVICE = "device=";
    private static final String FIELD_SALT = "salt=";
    private static final String FIELD_NONCE = "nonce=";
    private static final String FIELD_DATA = "data=";

    private final Path directory;

    public SessionStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "sessions"));
    }

    public SessionStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 读取某个对端设备的会话状态。不存在返回 null。
     */
    public SessionState load(String selfUsername, String peer, String deviceId, String password)
        throws IdentityException {
        return loadAt(selfUsername, peer, deviceId, password, false);
    }

    /**
     * 读取「对方发起」的那条棘轮。与我方发起链分文件存放，避免互相覆盖。
     */
    public SessionState loadInbound(String selfUsername, String peer, String deviceId, String password)
        throws IdentityException {
        return loadAt(selfUsername, peer, deviceId, password, true);
    }

    private SessionState loadAt(String selfUsername, String peer, String deviceId, String password,
        boolean inbound) throws IdentityException {
        Path file = inbound ? inboundFileOf(selfUsername, peer, deviceId)
            : fileOf(selfUsername, peer, deviceId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String salt = readField(content, FIELD_SALT);
            String nonce = readField(content, FIELD_NONCE);
            String data = readField(content, FIELD_DATA);
            byte[] plain = SecretBox.open(password, aad(selfUsername, peer, deviceId, inbound),
                Base64.getDecoder().decode(salt), Base64.getDecoder().decode(nonce),
                Base64.getDecoder().decode(data));
            String body = new String(plain, StandardCharsets.UTF_8);
            Hkdf.wipe(plain);
            SessionState state = deserialize(peer, body);
            // 以调用方指定的设备为准：该值来自文件名，是可信来源。
            // 旧版本保存的 state.deviceId 可能仍是默认值，这里必须覆盖，
            // 否则重载后构造出的会话键与发送时的键不一致。
            state.setDeviceId(deviceId);
            return state;
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("读取会话状态失败：" + e.getMessage());
        }
    }

    /**
     * 保存会话状态。原子写，返回后即可认为落盘成功。
     */
    public void save(String selfUsername, String peer, String deviceId, SessionState state,
        String password) throws IdentityException {
        saveAt(selfUsername, peer, deviceId, state, password, false);
    }

    public void saveInbound(String selfUsername, String peer, String deviceId, SessionState state,
        String password) throws IdentityException {
        saveAt(selfUsername, peer, deviceId, state, password, true);
    }

    private void saveAt(String selfUsername, String peer, String deviceId, SessionState state,
        String password, boolean inbound) throws IdentityException {
        if (state == null || state.getPeer() == null) {
            throw new IdentityException("会话状态为空，无法保存");
        }
        String device = deviceId == null || deviceId.isEmpty() ? "default" : deviceId;
        Path target = inbound ? inboundFileOf(selfUsername, peer, device)
            : fileOf(selfUsername, peer, device);
        Path temp = null;
        try {
            String json = serialize(state);
            SecretBox.Sealed sealed = SecretBox.seal(password,
                aad(selfUsername, peer, device, inbound),
                json.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(512);
            sb.append(FIELD_PEER).append(peer).append('\n');
            sb.append(FIELD_DEVICE).append(device).append('\n');
            sb.append(FIELD_SALT).append(Base64.getEncoder().encodeToString(sealed.getSalt())).append('\n');
            sb.append(FIELD_NONCE).append(Base64.getEncoder().encodeToString(sealed.getNonce())).append('\n');
            sb.append(FIELD_DATA).append(Base64.getEncoder().encodeToString(sealed.getCiphertext())).append('\n');

            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "session-", ".tmp");
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8));
            restrictPermissions(temp);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            }
            if (e instanceof IdentityException) {
                throw (IdentityException) e;
            }
            throw new IdentityException("保存会话状态失败：" + e.getMessage());
        }
    }

    public void delete(String selfUsername, String peer, String deviceId) {
        try {
            Files.deleteIfExists(fileOf(selfUsername, peer, deviceId));
        } catch (IOException e) {
            log.warn("删除会话文件失败：{}", e.getMessage());
        }
    }

    public void deleteInbound(String selfUsername, String peer, String deviceId) {
        try {
            Files.deleteIfExists(inboundFileOf(selfUsername, peer, deviceId));
        } catch (IOException e) {
            log.warn("删除应答会话文件失败：{}", e.getMessage());
        }
    }

    /**
     * 列出本地已有会话的标识，形如 {@code peer@device}。
     */
    public List<String> listPeers(String selfUsername) {
        List<String> peers = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return peers;
        }
        String prefix = sanitize(selfUsername) + SEP;
        java.io.File[] files = directory.toFile().listFiles();
        if (files == null) {
            return peers;
        }
        for (java.io.File file : files) {
            String name = file.getName();
            // 应答链单独放在 *.inbound.session，不能当成另一台设备
            if (name.endsWith(INBOUND_SUFFIX)) {
                continue;
            }
            if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) {
                continue;
            }
            String middle = name.substring(prefix.length(), name.length() - SUFFIX.length());
            String[] parts = middle.split(SEP);
            if (parts.length == 2) {
                peers.add(parts[0] + "@" + parts[1]);
            } else {
                // 兼容单设备时期的文件名（只有 peer）
                peers.add(middle + "@default");
            }
        }
        return peers;
    }

    private Path fileOf(String selfUsername, String peer, String deviceId) {
        String device = deviceId == null || deviceId.isEmpty() ? "default" : deviceId;
        return directory.resolve(sanitize(selfUsername) + SEP + sanitize(peer) + SEP
            + sanitize(device) + SUFFIX);
    }

    private Path inboundFileOf(String selfUsername, String peer, String deviceId) {
        String device = deviceId == null || deviceId.isEmpty() ? "default" : deviceId;
        return directory.resolve(sanitize(selfUsername) + SEP + sanitize(peer) + SEP
            + sanitize(device) + INBOUND_SUFFIX);
    }

    /**
     * AAD 绑定「谁和谁的哪个设备」，防止会话文件被跨账号或跨设备搬运。
     */
    private String aad(String selfUsername, String peer, String deviceId, boolean inbound) {
        String device = deviceId == null || deviceId.isEmpty() ? "default" : deviceId;
        return selfUsername + "|" + peer + "|" + device + (inbound ? "|in" : "");
    }

    private String sanitize(String name) {
        return name == null ? "unknown"
            : name.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
    }

    private void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("无法设置会话文件权限：{}", e.getMessage());
        }
    }

    private String readField(String content, String prefix) throws IdentityException {
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new IdentityException("会话文件缺少字段：" + prefix);
    }

    // ==================== 序列化 ====================

    private String serialize(SessionState state) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("createdAt=").append(state.getCreatedAt()).append('\n');
        sb.append("rootKey=").append(encode(state.getRootKey())).append('\n');
        sb.append("sendChainKey=").append(encode(state.getSendChainKey())).append('\n');
        sb.append("recvChainKey=").append(encode(state.getRecvChainKey())).append('\n');
        sb.append("dhSelfPrivate=").append(nullSafe(state.getDhSelfPrivate())).append('\n');
        sb.append("dhSelfPublic=").append(nullSafe(state.getDhSelfPublic())).append('\n');
        sb.append("dhRemotePublic=").append(nullSafe(state.getDhRemotePublic())).append('\n');
        sb.append("sendCount=").append(state.getSendCount()).append('\n');
        sb.append("recvCount=").append(state.getRecvCount()).append('\n');
        sb.append("prevSendCount=").append(state.getPrevSendCount()).append('\n');
        // 会话角色决定收到对方握手参数时是否要重建接收链，必须持久化
        sb.append("initiator=").append(state.isInitiator()).append('\n');
        // 发起方的握手要整组留下，重启后发出的消息还得带上。
        // 应答方没有身份公钥，只有对方的临时公钥，用来认出同一条链。
        // 以前只在 hasPendingHandshake 为真时才写，应答方的公钥因此丢了，
        // 重启后会把后续消息当成新的 X3DH，再去找已经删除的一次性预密钥。
        if (state.getHandshakeSenderEphemeralKey() != null) {
            sb.append("hsEphKey=").append(state.getHandshakeSenderEphemeralKey()).append('\n');
        }
        if (state.getHandshakeSenderIdentityKey() != null) {
            sb.append("hsIdKey=").append(state.getHandshakeSenderIdentityKey()).append('\n');
            sb.append("hsUsedOpk=").append(state.isHandshakeUsedOneTimePreKey()).append('\n');
            if (state.getHandshakeOneTimePreKeyId() != null) {
                sb.append("hsOpkId=").append(state.getHandshakeOneTimePreKeyId()).append('\n');
            }
        }
        for (Map.Entry<String, String> entry : state.getSkippedKeys().entrySet()) {
            sb.append("skipped=").append(entry.getKey()).append(',').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private SessionState deserialize(String peer, String content) {
        SessionState state = new SessionState();
        state.setPeer(peer);
        Map<String, String> skipped = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx);
            String value = line.substring(idx + 1).trim();
            switch (key) {
                case "createdAt":
                    state.setCreatedAt(parseLong(value));
                    break;
                case "rootKey":
                    state.setRootKey(decode(value));
                    break;
                case "sendChainKey":
                    state.setSendChainKey(decode(value));
                    break;
                case "recvChainKey":
                    state.setRecvChainKey(decode(value));
                    break;
                case "dhSelfPrivate":
                    state.setDhSelfPrivate(emptyToNull(value));
                    break;
                case "dhSelfPublic":
                    state.setDhSelfPublic(emptyToNull(value));
                    break;
                case "dhRemotePublic":
                    state.setDhRemotePublic(emptyToNull(value));
                    break;
                case "sendCount":
                    state.setSendCount((int) parseLong(value));
                    break;
                case "recvCount":
                    state.setRecvCount((int) parseLong(value));
                    break;
                case "prevSendCount":
                    state.setPrevSendCount((int) parseLong(value));
                    break;
                case "initiator":
                    state.setInitiator(Boolean.parseBoolean(value));
                    break;
                case "hsIdKey":
                    state.setHandshakeSenderIdentityKey(emptyToNull(value));
                    break;
                case "hsEphKey":
                    state.setHandshakeSenderEphemeralKey(emptyToNull(value));
                    break;
                case "hsUsedOpk":
                    state.setHandshakeUsedOneTimePreKey(Boolean.parseBoolean(value));
                    break;
                case "hsOpkId":
                    state.setHandshakeOneTimePreKeyId((int) parseLong(value));
                    break;
                case "skipped":
                    int comma = value.indexOf(',');
                    if (comma > 0) {
                        skipped.put(value.substring(0, comma), value.substring(comma + 1));
                    }
                    break;
                default:
                    break;
            }
        }
        state.setSkippedKeys(new LinkedHashMap<String, String>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > SessionState.MAX_SKIPPED;
            }
        });
        state.getSkippedKeys().putAll(skipped);
        return state;
    }

    private String encode(byte[] bytes) {
        return bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] decode(String value) {
        return value == null || value.isEmpty() ? null : Base64.getDecoder().decode(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
