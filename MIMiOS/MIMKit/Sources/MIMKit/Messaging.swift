import Foundation

/// 文本收发：把 `MimCore` / `SessionCrypto` / `MIMClient` 串起来。
///
/// 会话存储（含私钥）由调用方持有并负责加密落盘；这里只管「用一次、还回来」。
/// 会话索引（sessionId / 标签）单独存：`SessionStore` 对 Swift 是不透明 JSON，
/// 与其去猜它的内部结构，不如自己维护一张小表。
public final class MessagingSession {

    public struct Sent: Sendable {
        public let clientMsgId: String
        /// 一次性撤回凭证：只在本机保存，服务端只存哈希。
        public let recallToken: String
        /// 这条消息所属会话的标签（多设备同步要用它定位原消息）。
        public let tag: String
    }

    public struct Received: Sendable {
        public let peer: String
        public let text: String
        public let clientMsgId: String
        public let recalled: Bool
        public let sessionId: String
        /// 原始消息时间（同步副本带的是原时间，不是收到时间）。
        public let epochMs: Int64?
        /// 由哪台设备转发过来的；nil 表示正常实时消息。
        public let syncedFrom: String?
        /// 这条消息所属会话的标签。
        public let tag: String?
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

    /// 与某人所有设备会话的标签（撤回、清除都要按标签定位）。
    ///
    /// 服务端对撤回强制要求 tags：没有标签它无法确定要动哪段密文。
    public func tags(for peer: String) -> [String] {
        let prefix = "\(peer)|"
        let collected = sessions
            .filter { $0.key.hasPrefix(prefix) }
            .map(\.value.tag)
            .filter { !$0.isEmpty }
        return Array(Set(collected))
    }

    /// 发出文本，返回逻辑消息 id 与撤回凭证（调用方要存下来才能撤回）。
    @discardableResult
    public func sendText(to peer: String, text: String) async throws -> Sent {
        let keys = try await client.peerDevices(peer)
        guard !keys.isEmpty else {
            throw MIMApiError.failed(0, "对方还没有可用设备")
        }
        let clientMsgId = UUID().uuidString.lowercased()
        // 撤回凭证：32 字节随机数的十六进制，只在本机保存。
        let recallToken = (0..<32).map { _ in String(format: "%02x", Int.random(in: 0...255)) }.joined()
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
                    sessionId: existing.sessionId,
                    recallToken: recallToken
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
                sessionId: composed.sessionId,
                recallToken: recallToken
            ))
        }

        try await client.sendReplicas(copies)
        return Sent(
            clientMsgId: clientMsgId,
            recallToken: recallToken,
            tag: copies.first?.conversationTag ?? ""
        )
    }

    /// 把本机历史加密转发给同一账号的另一台设备（响应 100022）。
    ///
    /// 与桌面端同一套：每条消息包成 `SyncPayload`，用**源设备身份私钥**对
    /// `clientMsgId|tag|from|to|device` 签名，再用与目标设备之间的会话加密。
    /// 服务端只能看到密文与归属字段，看不到内容。
    ///
    /// - Parameters:
    ///   - deviceId: 目标设备（发起请求的那台）
    ///   - messages: 候选历史（调用方负责按时间/条数筛过）
    /// - Returns: 实际转发的条数
    @discardableResult
    public func exportHistory(
        to deviceId: String,
        messages candidates: [(id: String, peer: String, fromMe: Bool, text: String,
                               epochMs: Int64, payload: String?, tag: String?)],
        identityPrivateHex: String,
        identityPublicHex: String
    ) async throws -> Int {
        guard let selfName = client.username, !selfName.isEmpty,
              !deviceId.isEmpty, deviceId != self.deviceId else {
            return 0
        }
        let keys = try await client.peerDevices(selfName)
        guard let deviceKey = keys.first(where: { $0.deviceId == deviceId }) else { return 0 }

        var replicas: [OutgoingCopy] = []
        var replicaPeers: [String] = []
        var certificate: SenderCertificate?
        let key = Self.key(selfName, deviceId)

        for item in candidates {
            let contact = item.peer
            guard !contact.isEmpty, contact != selfName else { continue }
            let payload = SyncPayloadForExport(
                clientMsgId: item.id,
                // **必须用原消息自己的标签**：服务端按 client_msg_id + 标签找原消息。
                // 老消息没存标签时，回落到「与这个联系人会话的标签」——
                // 绝不能退成「与目标设备会话的标签」，那是两个不同的会话。
                tag: item.tag ?? tagForPeer(item.peer)
                    ?? UUID().uuidString.replacingOccurrences(of: "-", with: ""),
                from: item.fromMe ? selfName : contact,
                to: item.fromMe ? contact : selfName,
                device: deviceId,
                clientTime: item.epochMs,
                text: item.text,
                media: item.payload
            )
            let json = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
            let signature = try MimCore.sign(
                identityPrivateHex: identityPrivateHex,
                payload: Data(payload.canonical.utf8)
            )

            if let existing = sessions[key] {
                let sealed = try SessionCrypto.composeSealed(
                    store: store, sessionId: existing.sessionId, plaintext: json
                )
                store = sealed.store
                replicaPeers.append(contact)
                replicas.append(OutgoingCopy(
                    clientMsgId: payload.clientMsgId,
                    conversationTag: existing.tag,
                    toUsername: selfName,
                    targetDeviceId: deviceId,
                    alg: Self.algorithm,
                    iv: sealed.wire.iv,
                    ciphertext: sealed.wire.ciphertext,
                    ratchetPublic: sealed.wire.dh,
                    previousChainLength: sealed.wire.pn,
                    messageNumber: sealed.wire.n,
                    clientTime: item.epochMs,
                    sealed: true,
                    sessionId: existing.sessionId,
                    recallToken: nil,
                    syncOriginalFrom: payload.from,
                    syncOriginalTo: payload.to,
                    syncOriginalDevice: selfName,
                    syncSignature: signature
                ))
                continue
            }

            // 与目标设备还没有会话：走首条消息（X3DH + 证书信封）。
            let preKeys = try await client.preKeys(selfName, deviceId: deviceId)
            if certificate == nil { certificate = try await client.issueCertificate() }
            guard let certificate else { throw MIMApiError.failed(0, "缺少发送者证书") }
            let sessionId = UUID().uuidString.lowercased()
            let composed = try MimCore.composeFirstMessage(ComposeFirstRequest(
                identityPrivate: identityPrivateHex,
                identityPublic: identityPublicHex,
                peerIdentityPublic: try Self.hex(fromBase64: preKeys.identityPublicKey),
                peerSignedPreKeyId: preKeys.signedPreKeyId,
                peerSignedPreKeyPublic: try Self.hex(fromBase64: preKeys.signedPreKeyPublic),
                peerOneTimePreKeyId: preKeys.oneTimePreKeyId,
                peerOneTimePreKeyPublic: try preKeys.oneTimePreKeyPublic.map(Self.hex(fromBase64:)),
                plaintext: json,
                conversationTag: nil,
                sessionId: sessionId,
                certificate: certificate
            ))
            store = try SessionCrypto.insert(
                store: store, sessionId: composed.sessionId, peer: selfName,
                deviceId: deviceId, state: composed.state, tags: [composed.conversationTag]
            )
            sessions[key] = SessionRef(sessionId: composed.sessionId, tag: composed.conversationTag)
            replicaPeers.append(contact)
            replicas.append(OutgoingCopy(
                clientMsgId: payload.clientMsgId,
                conversationTag: composed.conversationTag,
                toUsername: selfName,
                targetDeviceId: deviceId,
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
                clientTime: item.epochMs,
                sealed: true,
                sessionId: composed.sessionId,
                recallToken: nil,
                syncOriginalFrom: payload.from,
                syncOriginalTo: payload.to,
                syncOriginalDevice: selfName,
                syncSignature: signature
            ))
            // 首条消息之后会话已建立，继续循环会走上面的「已建会话」分支。
            continue
        }

        guard !replicas.isEmpty else { return 0 }
        // 服务端按 `client_msg_id + 会话标签` 找原消息，一条不合格就整批拒绝。
        // 所以：先整批试；失败则逐条试，且每条都可在「已知标签候选」里换着试
        // （老消息没存标签时只能靠候选恢复）。
        if (try? await client.sendSyncReplicas(replicas)) != nil {
            return replicas.count
        }
        var sent = 0
        for (index, replica) in replicas.enumerated() {
            let peer = index < replicaPeers.count ? replicaPeers[index] : ""
            for tag in tagCandidates(peer: peer, preferred: replica.conversationTag) {
                var attempt = replica
                attempt.conversationTag = tag
                if (try? await client.sendSyncReplicas([attempt])) != nil {
                    sent += 1
                    break
                }
            }
        }
        return sent
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
                    recalled: true, sessionId: sessionId, epochMs: nil,
                    syncedFrom: nil, tag: nil
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
                if copy.isSyncCopy {
                    if let synced = try await decodeSyncCopy(
                        copy, plaintext: opened.plaintext, sessionId: sessionId
                    ) {
                        received.append(synced)
                    }
                    continue
                }
                received.append(Received(
                    peer: opened.certificate.username,
                    text: opened.plaintext,
                    clientMsgId: copy.clientMsgId ?? "",
                    recalled: false,
                    sessionId: sessionId,
                    epochMs: nil,
                    syncedFrom: nil,
                    tag: opened.conversationTag
                ))
                continue
            }

            let opened = try SessionCrypto.openSealed(store: store, sessionId: sessionId, wire: wire)
            store = opened.store
            let peer = sessions.first { $0.value.sessionId == sessionId }?
                .key.components(separatedBy: "|").first ?? ""
            if copy.isSyncCopy {
                if let synced = try await decodeSyncCopy(
                    copy, plaintext: opened.plaintext, sessionId: sessionId
                ) {
                    received.append(synced)
                }
                continue
            }
            received.append(Received(
                peer: peer, text: opened.plaintext, clientMsgId: copy.clientMsgId ?? "",
                recalled: false, sessionId: sessionId, epochMs: nil,
                syncedFrom: nil, tag: sessions.first { $0.value.sessionId == sessionId }?.value.tag
            ))
        }
        // 处理完再确认：先 ack 后处理的话，解密失败的消息就再也拉不到了。
        if let maxId = copies.compactMap({ $0.id }).max() {
            try? await client.markDelivered(beforeId: maxId)
        }
        return received
    }

    /// 解一条同步副本：验签 → 取原始归属 → 还原本来的对话与时间。
    ///
    /// 验签用的是**源设备的身份公钥**（我自己的另一台设备），不是对方账号的——
    /// 同步副本的合法性由「我的设备证明这条历史确实属于我」保证。
    private func decodeSyncCopy(
        _ copy: IncomingCopy, plaintext: String, sessionId: String
    ) async throws -> Received? {
        guard let sourceDevice = copy.syncFromDevice, !sourceDevice.isEmpty,
              let payload = try? JSONDecoder().decode(SyncPayload.self, from: Data(plaintext.utf8)),
              let signature = copy.syncSignature, !signature.isEmpty,
              let selfName = client.username, !selfName.isEmpty else {
            return nil
        }
        let keys = try await client.peerDevices(selfName)
        guard let key = keys.first(where: { $0.deviceId == sourceDevice }),
              let publicDer = Data(base64Encoded: key.publicKey),
              payload.verify(signatureBase64: signature, sourcePublicKeyDER: publicDer) else {
            return nil
        }
        // 原消息属于「我和另一个人的会话」：谁发的就归到谁，另一个人是会话对象。
        let contact = payload.from == selfName ? payload.to : payload.from
        guard !contact.isEmpty, contact != selfName else { return nil }
        return Received(
            peer: contact,
            text: payload.text,
            clientMsgId: payload.clientMsgId,
            recalled: false,
            sessionId: sessionId,
            epochMs: payload.clientTime,
            syncedFrom: payload.device,
            tag: payload.tag
        )
    }

    /// 与某个联系人（任一设备）会话的标签；老消息补标签时用。
    private func tagForPeer(_ peer: String) -> String? {
        sessions.first { $0.key.hasPrefix("\(peer)|") }?.value.tag
    }

    /// 某个联系人的所有已知标签，首选 `preferred`。
    ///
    /// 一条逻辑消息在不同接收设备上的标签不同；手上可能只知道其中一个，
    /// 服务端又只认「原消息那一份」，所以挨个试是最省事的自愈办法。
    private func tagCandidates(peer: String, preferred: String?) -> [String] {
        var list: [String] = []
        if let preferred, !preferred.isEmpty { list.append(preferred) }
        for (key, ref) in sessions where key.hasPrefix("\(peer)|") {
            _ = key
            if !ref.tag.isEmpty && !list.contains(ref.tag) {
                list.append(ref.tag)
            }
        }
        return list
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
