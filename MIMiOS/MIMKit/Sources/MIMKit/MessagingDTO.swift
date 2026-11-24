import Foundation

/// 服务端消息相关 DTO（camelCase，与 Spring 侧一致）。

public struct PeerKey: Decodable, Sendable {
    public let deviceId: String
    public let publicKey: String
    public let algorithm: String?
}

public struct PreKeyBundle: Decodable, Sendable {
    public let username: String
    public let deviceId: String
    public let identityPublicKey: String
    public let identityKeyVersion: Int?
    public let signedPreKeyId: UInt32
    public let signedPreKeyPublic: String
    public let signedPreKeySignature: String?
    public let oneTimePreKeyId: UInt32?
    public let oneTimePreKeyPublic: String?
    public let supportsRecallToken: Bool?
    public let supportsSenderCert: Bool?
}

/// 一条出站副本（每个目标设备一份）。
public struct OutgoingCopy: Encodable, Sendable {
    public var clientMsgId: String
    public var conversationTag: String
    public var toUsername: String
    public var targetDeviceId: String
    public var alg: String
    public var iv: String
    public var ciphertext: String
    public var ratchetPublic: String
    public var previousChainLength: UInt32
    public var messageNumber: UInt32
    public var senderIdentityKey: String?
    public var senderEphemeralKey: String?
    public var senderUsedOneTimePreKey: Bool?
    public var consumedOneTimePreKeyId: UInt32?
    public var recipientSignedPreKeyId: UInt32?
    public var clientTime: Int64
    public var sealed: Bool
    public var sessionId: String
    public var recallToken: String?
    // 同步副本的归属（普通消息为 nil）；服务端按这四项 + 签名校验后才落库。
    public var syncOriginalFrom: String?
    public var syncOriginalTo: String?
    public var syncOriginalDevice: String?
    public var syncSignature: String?
}

/// 服务端下发的一条密文。
public struct IncomingCopy: Decodable, Sendable {
    public let id: Int64?
    public let clientMsgId: String?
    public let sessionId: String?
    public let recalled: Bool?
    public let iv: String?
    public let ciphertext: String?
    public let ratchetPublic: String?
    public let previousChainLength: UInt32?
    public let messageNumber: UInt32?
    public let senderIdentityKey: String?
    public let senderEphemeralKey: String?
    public let senderUsedOneTimePreKey: Bool?
    public let consumedOneTimePreKeyId: UInt32?
    public let recipientSignedPreKeyId: UInt32?
    public let clientTime: Int64?
    public let toUsername: String?

    // 多设备同步副本的归属信息（普通消息为 nil）。
    /// 发起转发的设备（我的另一台设备）。
    public let syncFromDevice: String?
    public let syncOriginalFrom: String?
    public let syncOriginalTo: String?
    public let syncOriginalDevice: String?
    /// 源设备用身份私钥对「归属 + 内容」的签名（Base64）。
    public let syncSignature: String?

    /// 是不是「本账号另一台设备转发过来的历史副本」。
    public var isSyncCopy: Bool {
        !(syncFromDevice ?? "").isEmpty
    }

    /// 首条消息带握手参数，之后只有会话内密文。
    public var isFirstMessage: Bool {
        !(senderIdentityKey ?? "").isEmpty && !(senderEphemeralKey ?? "").isEmpty
    }

    public var wire: WireMessage? {
        guard let iv, let ciphertext, let ratchetPublic else { return nil }
        return WireMessage(
            dh: ratchetPublic,
            pn: previousChainLength ?? 0,
            n: messageNumber ?? 0,
            iv: iv,
            ciphertext: ciphertext
        )
    }
}

/// 本地会话索引：`SessionStore` 对 Swift 是不透明 JSON，所以另存一张小表，
/// 记录「这台对端设备用哪个 sessionId / 哪个会话标签」。
public struct SessionRef: Codable, Equatable, Sendable {
    public let sessionId: String
    public var tag: String

    public init(sessionId: String, tag: String) {
        self.sessionId = sessionId
        self.tag = tag
    }
}

public struct MediaUploadAck: Decodable, Sendable {
    public let id: String?
    public let size: Int64?
}

public struct ChunkInitAck: Decodable, Sendable {
    public let uploadId: String?
    public let chunkSize: Int?
    public let totalChunks: Int?
}


/// 同步副本的明文载荷（与桌面端 `SyncPayloadDto` 逐字对应）。
///
/// 注意：它**不是**原消息的正文，而是「原消息的元信息 + 正文」的封装。
/// 直接当聊天内容显示会得到一坨 JSON。
public struct SyncPayload: Decodable, Sendable {
    public let v: Int
    public let kind: String
    public let clientMsgId: String
    /// 原消息的会话标签：服务端按它找原消息，签名也覆盖它。
    public let tag: String
    public let from: String
    public let to: String
    public let device: String
    public let clientTime: Int64
    public let text: String
    /// 原消息的媒体载荷（媒体 id + 一次性密钥），纯文本为 nil。
    public let media: String?

    /// 签名覆盖的规范串：`clientMsgId|tag|from|to|device`。
    public var canonical: String {
        "\(clientMsgId)|\(tag)|\(from)|\(to)|\(device)"
    }

    /// 验签：源设备的身份公钥（SPKI DER）+ Base64 签名。
    ///
    /// 抽成纯函数是为了能单测——这条路径在真机上很难构造环境
    /// （需要「另一台有历史的设备在线并主动转发」）。
    public func verify(signatureBase64: String, sourcePublicKeyDER: Data) -> Bool {
        MimCore.verify(
            identityPublic: sourcePublicKeyDER,
            payload: Data(canonical.utf8),
            signatureBase64: signatureBase64
        )
    }
}


/// 导出同步副本时使用的载荷（字段与 `SyncPayload` 一致，但需要 Encodable）。
struct SyncPayloadForExport: Encodable {
    let v = 1
    let kind = "sync"
    let clientMsgId: String
    let tag: String
    let from: String
    let to: String
    let device: String
    let clientTime: Int64
    let text: String
    let media: String?

    var canonical: String {
        "\(clientMsgId)|\(tag)|\(from)|\(to)|\(device)"
    }
}
