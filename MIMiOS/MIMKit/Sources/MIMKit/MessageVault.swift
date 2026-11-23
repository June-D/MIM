import Foundation
import MIMCoreABI

/// 本地消息库：会话状态 + 会话索引 + 聊天记录，整体加密后落盘。
///
/// 三样东西必须放在一起，因为它们互相依赖：
/// - `store`：Rust 的会话状态（含私钥），丢了就解不开后续消息；
/// - `sessions`：对端设备 → sessionId/标签，丢了就不知道该用哪条会话；
/// - `messages`：界面要展示的历史（明文）。
///
/// 加密用 `mim_state_encrypt`（AES-256-GCM），密钥 32 字节存在 Keychain，
/// **每次加密都换新 nonce**——复用 nonce 会直接毁掉 GCM 的安全性。
public final class MessageVault {

    public struct StoredMessage: Codable, Equatable, Sendable {
        public var id: String
        public var peer: String
        public var text: String
        public var epochMs: Int64
        public var fromMe: Bool
        public var kind: String
        public var recalled: Bool
        public var mediaId: String?
        public var mediaMime: String?
        public var mediaName: String?
        public var mediaSize: Int?
        /// Base64 的一次性媒体密钥（用来解密本地/服务端密文）。
        public var mediaKey: String?
        /// 语音时长（毫秒）；非语音为 nil。
        public var mediaDurationMs: Int64?
        public var syncedFrom: String?

        public init(
            id: String, peer: String, text: String, epochMs: Int64,
            fromMe: Bool, kind: String = "text", recalled: Bool = false,
            mediaId: String? = nil, mediaMime: String? = nil,
            mediaName: String? = nil, mediaSize: Int? = nil,
            mediaKey: String? = nil, mediaDurationMs: Int64? = nil,
            syncedFrom: String? = nil
        ) {
            self.id = id
            self.peer = peer
            self.text = text
            self.epochMs = epochMs
            self.fromMe = fromMe
            self.kind = kind
            self.recalled = recalled
            self.mediaId = mediaId
            self.mediaMime = mediaMime
            self.mediaName = mediaName
            self.mediaSize = mediaSize
            self.mediaKey = mediaKey
            self.mediaDurationMs = mediaDurationMs
            self.syncedFrom = syncedFrom
        }
    }

    public struct Snapshot: Codable, Sendable {
        public var store: JSONValue
        public var sessions: [String: SessionRef]
        public var messages: [StoredMessage]
        /// 每个会话读到哪儿了（epochMs）。未读数 = 这之后对方发来的条数。
        /// 用可选类型：老版本的库文件没有这个字段，缺省按「全未读」处理。
        public var readUpTo: [String: Int64]?

        public init(store: JSONValue = .emptyStore, sessions: [String: SessionRef] = [:],
                    messages: [StoredMessage] = [], readUpTo: [String: Int64]? = [:]) {
            self.store = store
            self.sessions = sessions
            self.messages = messages
            self.readUpTo = readUpTo
        }
    }

    private static let keyAccount = "state-key"

    private let fileURL: URL
    private let keychain: Keychain
    private let account: String

    public init(root: URL, keychain: Keychain = Keychain(), account: String) {
        self.fileURL = root.appendingPathComponent("messages.bin")
        self.keychain = keychain
        self.account = account
    }

    /// 读并解密；文件不存在、密钥变了、密文被改过都返回 nil（当作「没有历史」）。
    public func load() -> Snapshot? {
        guard let blob = try? Data(contentsOf: fileURL), !blob.isEmpty,
              let key = stateKey(createIfMissing: false) else {
            return nil
        }
        guard let plaintext = decrypt(blob: blob, key: key) else {
            return nil
        }
        return try? JSONDecoder().decode(Snapshot.self, from: plaintext)
    }

    /// 加密后原子写入。
    ///
    /// 先写临时文件再 rename：中途崩溃不会留下半份状态——半份状态比没有更糟，
    /// 它会让解密失败，看起来像「历史全丢了」。
    public func save(_ snapshot: Snapshot) throws {
        guard let key = stateKey(createIfMissing: true) else {
            throw MIMCoreError.state
        }
        let plaintext = try JSONEncoder().encode(snapshot)
        guard let blob = encrypt(plaintext: plaintext, key: key) else {
            throw MIMCoreError.state
        }
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let temp = fileURL.deletingLastPathComponent()
            .appendingPathComponent("messages.bin.tmp")
        try blob.write(to: temp, options: .atomic)
        _ = try? FileManager.default.replaceItemAt(fileURL, withItemAt: temp)
    }

    // MARK: - 媒体密文缓存

    /// 媒体密文缓存目录：与状态文件同根，文件名就是媒体 id。
    ///
    /// 缓存的是**密文**而不是明文：解密结果只在内存里停留，落到磁盘上的东西
    /// 即使被拿到也解不开（密钥随消息密文走，不在这个目录里）。
    private var mediaDirectory: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("media", isDirectory: true)
    }

    public func saveMedia(_ id: String, blob: Data) {
        try? FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        try? blob.write(to: mediaDirectory.appendingPathComponent(id))
    }

    public func loadMedia(_ id: String) -> Data? {
        try? Data(contentsOf: mediaDirectory.appendingPathComponent(id))
    }

    // MARK: - 密钥与加解密

    private func stateKey(createIfMissing: Bool) -> Data? {
        if let existing = keychain.get(account: Self.keyAccount), existing.count == 32 {
            return existing
        }
        guard createIfMissing else { return nil }
        var key = Data(count: 32)
        let status = key.withUnsafeMutableBytes { buffer -> Int32 in
            guard let base = buffer.bindMemory(to: UInt8.self).baseAddress else { return -1 }
            return mim_random_bytes(base, 32)
        }
        guard status == 0 else { return nil }
        try? keychain.set(key, account: Self.keyAccount)
        return key
    }

    private func encrypt(plaintext: Data, key: Data) -> Data? {
        var nonce = Data(count: 12)
        let generated = nonce.withUnsafeMutableBytes { buffer -> Int32 in
            guard let base = buffer.bindMemory(to: UInt8.self).baseAddress else { return -1 }
            return mim_random_bytes(base, 12)
        }
        guard generated == 0 else { return nil }

        var out: UnsafeMutablePointer<UInt8>?
        var outLen = 0
        let code: Int32 = plaintext.withUnsafeBytes { plain in
            key.withUnsafeBytes { keyBytes in
                nonce.withUnsafeBytes { nonceBytes in
                    mim_state_encrypt(
                        plain.bindMemory(to: UInt8.self).baseAddress, plain.count,
                        keyBytes.bindMemory(to: UInt8.self).baseAddress, keyBytes.count,
                        nonceBytes.bindMemory(to: UInt8.self).baseAddress, nonceBytes.count,
                        &out, &outLen
                    )
                }
            }
        }
        defer { mim_bytes_free(out, outLen) }
        guard code == 0, let out else { return nil }
        return Data(bytes: out, count: outLen)
    }

    private func decrypt(blob: Data, key: Data) -> Data? {
        var out: UnsafeMutablePointer<UInt8>?
        var outLen = 0
        let code: Int32 = blob.withUnsafeBytes { raw in
            key.withUnsafeBytes { keyBytes in
                mim_state_decrypt(
                    raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
                    keyBytes.bindMemory(to: UInt8.self).baseAddress, keyBytes.count,
                    &out, &outLen
                )
            }
        }
        defer { mim_bytes_free(out, outLen) }
        guard code == 0, let out else { return nil }
        return Data(bytes: out, count: outLen)
    }
}
