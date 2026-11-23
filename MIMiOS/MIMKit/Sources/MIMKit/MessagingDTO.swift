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
