import Foundation

/// 文本收发：把 `MimCore` / `SessionCrypto` / `MIMClient` 串起来。
///
/// 会话存储（含私钥）由调用方持有并负责加密落盘；这里只管「用一次、还回来」。
/// 会话索引（sessionId / 标签）单独存：`SessionStore` 对 Swift 是不透明 JSON，
/// 与其去猜它的内部结构，不如自己维护一张小表。
public final class MessagingSession {

    public struct Received: Sendable {
        public let peer: String
        public let text: String
        public let clientMsgId: String
        public let recalled: Bool
        public let sessionId: String
    }

    private let client: MIMClient
    private let bundle: KeyBundle
    private let deviceId: String

    /// 服务端只接受这一串算法标识（与桌面端常量一致），写错会被拒。
    static let algorithm = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM"

    /// 索引键：`对端用户名|对端设备 id`——同一用户可能有多台设备，各自一条会话。
    private static func key(_ peer: String, _ deviceId: String) -> String {
        "\(peer)|\(deviceId)"
    }

    public init(
        client: MIMClient,
        bundle: KeyBundle,
        store: JSONValue = .emptyStore,
        sessions: [String: SessionRef] = [:]
    ) {
        self.client = client
        self.bundle = bundle
        self.deviceId = client.deviceId
        self.store = store
        self.sessions = sessions
    }

    /// 会话存储与索引都要落盘，所以提供一次性导出的入口。
    public var snapshot: (store: JSONValue, sessions: [String: SessionRef]) {
        (store, sessions)
    }

    /// 当前会话存储（调用方应加密后落盘）。
    public private(set) var store: JSONValue

    /// 当前会话索引（调用方应与 store 一起落盘）。
    public private(set) var sessions: [String: SessionRef]

    /// 发出文本，返回实际投递的设备数。
    @discardableResult
    public func sendText(to peer: String, text: String) async throws -> Int {
        let keys = try await client.peerDevices(peer)
        guard !keys.isEmpty else {
            throw MIMApiError.failed(0, "对方还没有可用设备")
        }
        let clientMsgId = UUID().uuidString.lowercased()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var copies: [OutgoingCopy] = []
        var certificate: SenderCertificate?

        for key in keys {
            if let existing = sessions[Self.key(peer, key.deviceId)] {
                // 已有会话：双棘轮推进。
                let sealed = try SessionCrypto.composeSealed(
                    store: store, sessionId: existing.sessionId, plaintext: text
                )
                store = sealed.store
                copies.append(OutgoingCopy(
                    clientMsgId: clientMsgId,
                    conversationTag: existing.tag,
                    toUsername: peer,
                    targetDeviceId: key.deviceId,
                    alg: Self.algorithm,
                    iv: sealed.wire.iv,
                    ciphertext: sealed.wire.ciphertext,
                    ratchetPublic: sealed.wire.dh,
                    previousChainLength: sealed.wire.pn,
                    messageNumber: sealed.wire.n,
                    clientTime: now,
                    sealed: true,
                    sessionId: existing.sessionId
                ))
                continue
            }

            // 首次对话：X3DH + 证书信封，交给 Rust 组装。
            let preKeys = try await client.preKeys(peer, deviceId: key.deviceId)
            if certificate == nil {
                certificate = try await client.issueCertificate()
            }
            guard let certificate else { throw MIMApiError.failed(0, "缺少发送者证书") }
            let sessionId = UUID().uuidString.lowercased()
            let composed = try MimCore.composeFirstMessage(ComposeFirstRequest(
                identityPrivate: bundle.identity.privateKey,
                identityPublic: bundle.identity.publicKey,
                // 服务端给的是 Base64(SPKI)，Rust 要的是 hex——不转换会 handshake 失败。
                peerIdentityPublic: try Self.hex(fromBase64: preKeys.identityPublicKey),
                peerSignedPreKeyId: preKeys.signedPreKeyId,
                peerSignedPreKeyPublic: try Self.hex(fromBase64: preKeys.signedPreKeyPublic),
                peerOneTimePreKeyId: preKeys.oneTimePreKeyId,
                peerOneTimePreKeyPublic: try preKeys.oneTimePreKeyPublic.map(Self.hex(fromBase64:)),
                plaintext: text,
                conversationTag: nil,
                sessionId: sessionId,
                certificate: certificate
            ))
            store = try SessionCrypto.insert(
                store: store, sessionId: composed.sessionId, peer: peer,
                deviceId: key.deviceId, state: composed.state, tags: [composed.conversationTag]
            )
            sessions[Self.key(peer, key.deviceId)] = SessionRef(
                sessionId: composed.sessionId, tag: composed.conversationTag
            )
            copies.append(OutgoingCopy(
                clientMsgId: clientMsgId,
                conversationTag: composed.conversationTag,
                toUsername: peer,
                targetDeviceId: key.deviceId,
                alg: composed.alg,
                iv: composed.wire.iv,
                ciphertext: composed.wire.ciphertext,
                ratchetPublic: composed.wire.dh,
                previousChainLength: composed.wire.pn,
                messageNumber: composed.wire.n,
                senderIdentityKey: composed.senderIdentityKey,
                senderEphemeralKey: composed.senderEphemeralKey,
                senderUsedOneTimePreKey: composed.usedOneTimePreKey,
                consumedOneTimePreKeyId: composed.consumedOneTimePreKeyId,
                recipientSignedPreKeyId: composed.recipientSignedPreKeyId,
                clientTime: now,
                sealed: true,
                sessionId: composed.sessionId
            ))
        }

        try await client.sendReplicas(copies)
        return copies.count
    }

    /// 拉取未投递消息并解密。
    public func receive() async throws -> [Received] {
        let copies = try await client.undelivered()
        guard !copies.isEmpty else { return [] }
        var received: [Received] = []
        for copy in copies {
            guard let sessionId = copy.sessionId else { continue }
            if copy.recalled == true {
                received.append(Received(
                    peer: "", text: "已撤回", clientMsgId: copy.clientMsgId ?? "",
                    recalled: true, sessionId: sessionId
                ))
                continue
            }
            guard let wire = copy.wire else { continue }

            if copy.isFirstMessage {
                let opened = try MimCore.openFirstMessage(OpenFirstRequest(
                    identityPrivate: bundle.identity.privateKey,
                    identityPublic: bundle.identity.publicKey,
                    signedPreKeyPrivate: try signedPreKey(byId: copy.recipientSignedPreKeyId).privateKey,
                    signedPreKeyPublic: try signedPreKey(byId: copy.recipientSignedPreKeyId).publicKey,
                    oneTimePreKeyPrivate: oneTimePreKeyPrivate(id: copy.consumedOneTimePreKeyId),
                    peerIdentityPublic: copy.senderIdentityKey ?? "",
                    peerEphemeralPublic: copy.senderEphemeralKey ?? "",
                    usedOneTimePreKey: copy.senderUsedOneTimePreKey ?? false,
                    wire: wire
                ))
                let tag = opened.conversationTag ?? ""
                store = try SessionCrypto.insert(
                    store: store, sessionId: sessionId,
                    peer: opened.certificate.username,
                    deviceId: opened.certificate.deviceId,
                    state: opened.state,
                    tags: opened.conversationTag.map { [$0] } ?? []
                )
                sessions[Self.key(opened.certificate.username, opened.certificate.deviceId)] =
                    SessionRef(sessionId: sessionId, tag: tag)
                received.append(Received(
                    peer: opened.certificate.username,
                    text: opened.plaintext,
                    clientMsgId: copy.clientMsgId ?? "",
                    recalled: false,
                    sessionId: sessionId
                ))
                continue
            }

            let opened = try SessionCrypto.openSealed(store: store, sessionId: sessionId, wire: wire)
            store = opened.store
            let peer = sessions.first { $0.value.sessionId == sessionId }?
                .key.components(separatedBy: "|").first ?? ""
            received.append(Received(
                peer: peer, text: opened.plaintext, clientMsgId: copy.clientMsgId ?? "",
                recalled: false, sessionId: sessionId
            ))
        }
        // 处理完再确认：先 ack 后处理的话，解密失败的消息就再也拉不到了。
        if let maxId = copies.compactMap({ $0.id }).max() {
            try? await client.markDelivered(beforeId: maxId)
        }
        return received
    }

    /// Base64 → hex。转换失败说明服务端字段格式变了，必须报错而不是猜。
    private static func hex(fromBase64 value: String) throws -> String {
        guard let data = Data(base64Encoded: value) else {
            throw MIMApiError.failed(0, "公钥不是合法的 Base64")
        }
        return data.hexString
    }

    // MARK: - 本地密钥查找

    private func signedPreKey(byId id: UInt32?) throws -> SignedPreKey {
        guard let key = bundle.signedPreKeys.first(where: { $0.id == id })
            ?? bundle.signedPreKeys.max(by: { $0.id < $1.id }) else {
            throw MIMApiError.failed(0, "本地缺少签名预密钥")
        }
        return key
    }

    private func oneTimePreKeyPrivate(id: UInt32?) -> String? {
        guard let id else { return nil }
        return bundle.oneTimePreKeys.first { $0.id == id }?.privateKey
    }
}
