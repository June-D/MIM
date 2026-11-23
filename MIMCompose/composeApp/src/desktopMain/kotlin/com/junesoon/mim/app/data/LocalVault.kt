package com.junesoon.mim.app.data

import com.junesoon.mim.core.MimCoreNative
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 本地保险库：密钥包、会话存储、消息缓存。
 *
 * <p>所有内容都用 AES-256-GCM 加密（`MimCoreNative.encryptState`），
 * 密钥保存在 `key.bin` 并限制为 0600。桌面端只能做到「文件权限保护」；
 * Android/iOS 应把这段密钥换成 Keystore / Keychain。
 */
class LocalVault(
    private val root: Path,
    val username: String,
    val deviceId: String,
) {

    private val dir: Path = root.resolve(username).resolve(deviceId)
    private val mediaDir: Path = dir.resolve("media")

    init {
        Files.createDirectories(dir)
    }

    private val key: ByteArray by lazy { loadOrCreateKey() }

    fun loadBundle(): RustKeyBundle? =
        read("bundle.bin")?.let {
            runCatching { JsonCodec.decode<RustKeyBundle>(it) }.getOrNull()
        }

    fun saveBundle(bundle: RustKeyBundle) {
        write("bundle.bin", JsonCodec.encode(bundle))
    }

    fun loadStore(): String = read("sessions.bin") ?: EMPTY_STORE

    fun saveStore(storeJson: String) {
        write("sessions.bin", storeJson)
    }

    fun loadMessages(): List<CachedMessage> =
        read("messages.bin")?.let {
            runCatching { JsonCodec.decode<List<CachedMessage>>(it) }.getOrNull()
        }.orEmpty()

    fun saveMessages(messages: List<CachedMessage>) {
        write("messages.bin", JsonCodec.encode(messages))
    }

    fun loadOutbox(): List<OutboxEntry> =
        read("outbox.bin")?.let {
            runCatching { JsonCodec.decode<List<OutboxEntry>>(it) }.getOrNull()
        }.orEmpty()

    fun saveOutbox(entries: List<OutboxEntry>) {
        write("outbox.bin", JsonCodec.encode(entries))
    }

    /**
     * 媒体密文缓存。
     *
     * 存的是上传到服务端的那一份密文（已用消息密钥加密），不是明文；
     * 展示时按消息里的密钥临时解密。文件名只允许随机 id，避免路径穿越。
     */
    fun saveMedia(id: String, encrypted: ByteArray) {
        if (!MediaIds.isValid(id)) {
            return
        }
        Files.createDirectories(mediaDir)
        val file = mediaDir.resolve(id)
        Files.write(file, encrypted)
        restrict(file)
    }

    fun loadMedia(id: String): ByteArray? {
        if (!MediaIds.isValid(id)) {
            return null
        }
        val file = mediaDir.resolve(id)
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    fun deleteMedia(ids: Collection<String>) {
        for (id in ids) {
            if (MediaIds.isValid(id)) {
                runCatching { Files.deleteIfExists(mediaDir.resolve(id)) }
            }
        }
    }

    /** 本机屏蔽的联系人。屏蔽是本地行为：不通知服务端，也不影响对方。 */
    fun loadBlocked(): Set<String> =
        read("blocked.bin")?.let {
            runCatching { JsonCodec.decode<Set<String>>(it) }.getOrNull()
        }.orEmpty()

    fun saveBlocked(peers: Set<String>) {
        write("blocked.bin", JsonCodec.encode(peers))
    }

    fun loadServerIds(): Map<String, Long> =
        read("server_ids.bin")?.let {
            runCatching { JsonCodec.decode<Map<String, Long>>(it) }.getOrNull()
        }.orEmpty()

    fun saveServerIds(ids: Map<String, Long>) {
        write("server_ids.bin", JsonCodec.encode(ids))
    }

    /**
     * 置顶的会话。纯本地偏好，不上报服务端：服务端知道谁被置顶等于多一条关联面。
     */
    fun loadPinned(): Set<String> =
        read("pinned.bin")?.let {
            runCatching { JsonCodec.decode<Set<String>>(it) }.getOrNull()
        }.orEmpty()

    fun savePinned(peers: Set<String>) {
        write("pinned.bin", JsonCodec.encode(peers))
    }

    /**
     * 每个会话「已读到哪里」的时间水位（毫秒）。
     *
     * 只用来在打开会话时定位第一条未读；消息本身不受影响。
     */
    fun loadReadMarks(): Map<String, Long> =
        read("read_marks.bin")?.let {
            runCatching { JsonCodec.decode<Map<String, Long>>(it) }.getOrNull()
        }.orEmpty()

    fun saveReadMarks(marks: Map<String, Long>) {
        write("read_marks.bin", JsonCodec.encode(marks))
    }

    /**
     * 上次见到的对端设备安全码，key 形如 `联系人|设备`。
     *
     * 只保存安全码本身（公钥派生的摘要），不含任何密钥材料；
     * 对端换密钥后安全码会变，据此提醒用户重新核对。
     */
    fun loadSafetyCodes(): Map<String, String> =
        read("safety.bin")?.let {
            runCatching { JsonCodec.decode<Map<String, String>>(it) }.getOrNull()
        }.orEmpty()

    fun saveSafetyCodes(codes: Map<String, String>) {
        write("safety.bin", JsonCodec.encode(codes))
    }

    private fun loadOrCreateKey(): ByteArray {
        val file = dir.resolve("key.bin")
        if (Files.exists(file)) {
            return Files.readAllBytes(file)
        }
        val created = MimCoreNative.randomBytes(32)
        Files.write(file, created)
        restrict(file)
        return created
    }

    private fun read(name: String): String? {
        val file = dir.resolve(name)
        if (!Files.exists(file)) {
            return null
        }
        val sealed = String(Files.readAllBytes(file), Charsets.UTF_8)
        return runCatching {
            MimCoreNative.decryptState(sealed.toByteArray(Charsets.UTF_8), key)
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun write(name: String, content: String) {
        val nonce = MimCoreNative.randomBytes(12)
        val sealed = MimCoreNative.encryptState(content.toByteArray(Charsets.UTF_8), key, nonce)
        val file = dir.resolve(name)
        Files.write(file, sealed)
        restrict(file)
    }

    private fun restrict(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    companion object {
        const val EMPTY_STORE = "{\"sessions\":[]}"

        /** 默认位置：`~/.mim`，可用 `-Dmim.vault.dir` 覆盖（测试用）。 */
        fun defaultRoot(): Path =
            Paths.get(System.getProperty("mim.vault.dir") ?: "${System.getProperty("user.home")}/.mim")

        /** 每台安装一个稳定设备标识，存在用户名目录下。 */
        fun deviceIdFor(root: Path, username: String): String {
            val dir = root.resolve(username)
            Files.createDirectories(dir)
            val file = dir.resolve("device.txt")
            if (Files.exists(file)) {
                return String(Files.readAllBytes(file), Charsets.UTF_8).trim()
            }
            val created = "desktop-" + java.util.UUID.randomUUID().toString().take(8)
            Files.write(file, created.toByteArray(Charsets.UTF_8))
            return created
        }
    }
}

/** 消息缓存条目：重启后聊天仍可见。 */
@kotlinx.serialization.Serializable
data class CachedMessage(
    val contactId: String,
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val time: String,
    val epochMs: Long,
    val read: Boolean = false,
    val recalled: Boolean = false,
    /** 撤回凭证：只在本地缓存，服务端只存哈希。 */
    val recallToken: String? = null,
    /** 离线队列中的消息：正文已组装，等待投递。 */
    val pending: Boolean = false,
    /** 媒体消息的原始载荷 JSON；纯文本消息为 null。 */
    val payload: String? = null,
    /**
     * 服务端消息 id；旧版本写入的缓存没有这个字段。
     *
     * 对方执行「双方清除」后本机要对账：有 id 的按 id 判断，
     * 没有 id 的老数据按清除时间兜底。
     */
    val serverId: Long? = null,
    /** 同步副本的来源设备；普通消息为 null。 */
    val syncedFrom: String? = null,
)

/** 离线发送队列条目：密文已组装，重试只重发，不重新推进棘轮。 */
@kotlinx.serialization.Serializable
data class OutboxEntry(
    val clientMsgId: String,
    val recallToken: String,
    val contactId: String,
    val text: String,
    val epochMs: Long,
    val replicas: List<com.junesoon.mim.app.data.messaging.CipherMessageOut>,
)

/** 服务端媒体 id 的允许形状：32 字节随机数的 Base64URL。 */
object MediaIds {
    private val Pattern = Regex("^[A-Za-z0-9_-]{16,64}$")

    fun isValid(id: String?): Boolean = id != null && Pattern.matches(id)
}
