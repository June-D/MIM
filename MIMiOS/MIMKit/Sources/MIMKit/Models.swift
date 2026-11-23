import Foundation

/// 协议核心的 JSON DTO。
///
/// 字段名必须与 `MIMCoreRust/src/messaging.rs` 逐字对应（Rust 侧是 snake_case，
/// 这里用 camelCase 属性 + 编码策略转换）。`ContractTests` 会把其中几条关键字段名钉住，
/// 谁改了 Rust 结构体就会先红在测试上，而不是等到真机联调。
public struct IdentityKeyPair: Codable, Equatable {
    public let privateKey: String
    public let publicKey: String

    public init(privateKey: String, publicKey: String) {
        self.privateKey = privateKey
        self.publicKey = publicKey
    }
}

public struct SignedPreKey: Codable, Equatable {
    public let id: UInt32
    public let privateKey: String
    public let publicKey: String
    public let signature: String
    public let createdAt: Int64?

    public init(id: UInt32, privateKey: String, publicKey: String, signature: String, createdAt: Int64? = nil) {
        self.id = id
        self.privateKey = privateKey
        self.publicKey = publicKey
        self.signature = signature
        self.createdAt = createdAt
    }
}

public struct OneTimePreKey: Codable, Equatable {
    public let id: UInt32
    public let privateKey: String
    public let publicKey: String

    public init(id: UInt32, privateKey: String, publicKey: String) {
        self.id = id
        self.privateKey = privateKey
        self.publicKey = publicKey
    }
}

/// 密钥包：iOS 首次登录时生成，私钥进 Keychain，公钥部分上传服务端。
public struct KeyBundle: Codable, Equatable {
    public let version: UInt32
    public let identity: IdentityKeyPair
    public let signedPreKeys: [SignedPreKey]
    public let oneTimePreKeys: [OneTimePreKey]
    public let nextOneTimePreKeyId: UInt32?

    public init(version: UInt32, identity: IdentityKeyPair, signedPreKeys: [SignedPreKey],
                oneTimePreKeys: [OneTimePreKey], nextOneTimePreKeyId: UInt32? = nil) {
        self.version = version
        self.identity = identity
        self.signedPreKeys = signedPreKeys
        self.oneTimePreKeys = oneTimePreKeys
        self.nextOneTimePreKeyId = nextOneTimePreKeyId
    }
}

/// 发送者证书：随首条消息带给对端，用于把消息归到「谁发的」。
public struct SenderCertificate: Codable, Equatable {
    public let username: String
    public let deviceId: String
    public let identityKey: String
    public let issuedAt: Int64
    public let expiresAt: Int64
    public let keyId: String
    public let signature: String

    public init(username: String, deviceId: String, identityKey: String,
                issuedAt: Int64, expiresAt: Int64, keyId: String, signature: String) {
        self.username = username
        self.deviceId = deviceId
        self.identityKey = identityKey
        self.issuedAt = issuedAt
        self.expiresAt = expiresAt
        self.keyId = keyId
        self.signature = signature
    }
}

/// 双棘轮线格式。
public struct WireMessage: Codable, Equatable {
    public let dh: String
    public let pn: UInt32
    public let n: UInt32
    public let iv: String
    public let ciphertext: String

    public init(dh: String, pn: UInt32, n: UInt32, iv: String, ciphertext: String) {
        self.dh = dh
        self.pn = pn
        self.n = n
        self.iv = iv
        self.ciphertext = ciphertext
    }
}

/// 会话状态快照（含私钥）。必须加密后落盘——明文保存等于把身份交出去。
public struct StateSnapshot: Codable, Equatable {
    public let rootKey: String
    public let sendChainKey: String?
    public let recvChainKey: String?
    public let dhSelfPrivate: String
    public let dhSelfPublic: String
    public let dhRemotePublic: String?
    public let sendCount: UInt32
    public let recvCount: UInt32
    public let prevSendCount: UInt32
    /// 跳过的消息密钥。Rust 侧是必填数组（没有 serde default），
    /// 少传这一项整份状态会反序列化失败（表现为 invalidArgument）。
    public let skipped: [JSONValue]

    public init(rootKey: String, sendChainKey: String?, recvChainKey: String?,
                dhSelfPrivate: String, dhSelfPublic: String, dhRemotePublic: String?,
                sendCount: UInt32, recvCount: UInt32, prevSendCount: UInt32,
                skipped: [JSONValue] = []) {
        self.rootKey = rootKey
        self.sendChainKey = sendChainKey
        self.recvChainKey = recvChainKey
        self.dhSelfPrivate = dhSelfPrivate
        self.dhSelfPublic = dhSelfPublic
        self.dhRemotePublic = dhRemotePublic
        self.sendCount = sendCount
        self.recvCount = recvCount
        self.prevSendCount = prevSendCount
        self.skipped = skipped
    }
}

public struct ComposeFirstRequest: Codable, Equatable {
    public let identityPrivate: String
    public let identityPublic: String
    public let peerIdentityPublic: String
    public let peerSignedPreKeyId: UInt32
    public let peerSignedPreKeyPublic: String
    public let peerOneTimePreKeyId: UInt32?
    public let peerOneTimePreKeyPublic: String?
    public let plaintext: String
    public let conversationTag: String?
    public let sessionId: String
    public let certificate: SenderCertificate
    public let iv: String?

    public init(identityPrivate: String, identityPublic: String, peerIdentityPublic: String,
                peerSignedPreKeyId: UInt32, peerSignedPreKeyPublic: String,
                peerOneTimePreKeyId: UInt32?, peerOneTimePreKeyPublic: String?,
                plaintext: String, conversationTag: String?, sessionId: String,
                certificate: SenderCertificate, iv: String? = nil) {
        self.identityPrivate = identityPrivate
        self.identityPublic = identityPublic
        self.peerIdentityPublic = peerIdentityPublic
        self.peerSignedPreKeyId = peerSignedPreKeyId
        self.peerSignedPreKeyPublic = peerSignedPreKeyPublic
        self.peerOneTimePreKeyId = peerOneTimePreKeyId
        self.peerOneTimePreKeyPublic = peerOneTimePreKeyPublic
        self.plaintext = plaintext
        self.conversationTag = conversationTag
        self.sessionId = sessionId
        self.certificate = certificate
        self.iv = iv
    }
}

public struct ComposeFirstResponse: Codable, Equatable {
    public let wire: WireMessage
    public let state: StateSnapshot
    public let alg: String
    public let conversationTag: String
    public let sessionId: String
    public let senderIdentityKey: String
    public let senderEphemeralKey: String
    public let usedOneTimePreKey: Bool
    public let consumedOneTimePreKeyId: UInt32?
    public let recipientSignedPreKeyId: UInt32
}

public struct OpenFirstRequest: Codable, Equatable {
    public let identityPrivate: String
    public let identityPublic: String
    public let signedPreKeyPrivate: String
    public let signedPreKeyPublic: String
    public let oneTimePreKeyPrivate: String?
    public let peerIdentityPublic: String
    public let peerEphemeralPublic: String
    public let usedOneTimePreKey: Bool
    public let wire: WireMessage
    public let nextDhPrivate: String?
    public let nextDhPublic: String?

    public init(identityPrivate: String, identityPublic: String,
                signedPreKeyPrivate: String, signedPreKeyPublic: String,
                oneTimePreKeyPrivate: String?, peerIdentityPublic: String,
                peerEphemeralPublic: String, usedOneTimePreKey: Bool,
                wire: WireMessage, nextDhPrivate: String? = nil, nextDhPublic: String? = nil) {
        self.identityPrivate = identityPrivate
        self.identityPublic = identityPublic
        self.signedPreKeyPrivate = signedPreKeyPrivate
        self.signedPreKeyPublic = signedPreKeyPublic
        self.oneTimePreKeyPrivate = oneTimePreKeyPrivate
        self.peerIdentityPublic = peerIdentityPublic
        self.peerEphemeralPublic = peerEphemeralPublic
        self.usedOneTimePreKey = usedOneTimePreKey
        self.wire = wire
        self.nextDhPrivate = nextDhPrivate
        self.nextDhPublic = nextDhPublic
    }
}

public struct OpenFirstResponse: Codable, Equatable {
    public let plaintext: String
    public let certificate: SenderCertificate
    public let conversationTag: String?
    public let state: StateSnapshot
}
