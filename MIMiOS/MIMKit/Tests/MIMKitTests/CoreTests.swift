import XCTest
@testable import MIMKit

/// C ABI 封装层的端到端检查：密钥包 → 首条消息 → 对端拆开 → 身份签名。
///
/// 这些用例跑在 macOS 上（不需要 Xcode / 设备），验证的是 Swift 桥接写法本身。
final class CoreTests: XCTestCase {

    private let tag = "gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E"

    func testVersionIsExposed() {
        XCTAssertFalse(MimCore.version.isEmpty)
    }

    func testFirstMessageRoundTrip() throws {
        let alice = try MimCore.makeKeyBundle(oneTimePreKeys: 1)
        let bob = try MimCore.makeKeyBundle(oneTimePreKeys: 1)
        let bobSigned = try XCTUnwrap(bob.signedPreKeys.first)
        let bobOneTime = try XCTUnwrap(bob.oneTimePreKeys.first)

        let composed = try MimCore.composeFirstMessage(ComposeFirstRequest(
            identityPrivate: alice.identity.privateKey,
            identityPublic: alice.identity.publicKey,
            peerIdentityPublic: bob.identity.publicKey,
            peerSignedPreKeyId: bobSigned.id,
            peerSignedPreKeyPublic: bobSigned.publicKey,
            peerOneTimePreKeyId: bobOneTime.id,
            peerOneTimePreKeyPublic: bobOneTime.publicKey,
            plaintext: "iOS 第一条消息",
            conversationTag: tag,
            sessionId: "session-ios",
            certificate: SenderCertificate(
                username: "alice",
                deviceId: "alice-iphone",
                identityKey: Data(hex: alice.identity.publicKey).base64EncodedString(),
                issuedAt: 1_795_056_000_000,
                expiresAt: 1_795_142_400_000,
                keyId: String(repeating: "00", count: 32),
                signature: "c2lnbmF0dXJl"
            )
        ))

        XCTAssertEqual(composed.conversationTag, tag)
        XCTAssertEqual(composed.sessionId, "session-ios")
        XCTAssertTrue(composed.usedOneTimePreKey)

        let opened = try MimCore.openFirstMessage(OpenFirstRequest(
            identityPrivate: bob.identity.privateKey,
            identityPublic: bob.identity.publicKey,
            signedPreKeyPrivate: bobSigned.privateKey,
            signedPreKeyPublic: bobSigned.publicKey,
            oneTimePreKeyPrivate: bobOneTime.privateKey,
            peerIdentityPublic: composed.senderIdentityKey,
            peerEphemeralPublic: composed.senderEphemeralKey,
            usedOneTimePreKey: true,
            wire: composed.wire
        ))

        XCTAssertEqual(opened.plaintext, "iOS 第一条消息")
        XCTAssertEqual(opened.certificate.deviceId, "alice-iphone")
        XCTAssertEqual(opened.conversationTag, tag)
    }

    func testIdentitySignatureAndTampering() throws {
        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 0)
        let payload = Data("msg-1|\(tag)|alice|bob|device-9".utf8)

        let signature = try MimCore.sign(
            identityPrivateHex: bundle.identity.privateKey,
            payload: payload
        )
        XCTAssertFalse(signature.isEmpty)

        let publicKey = Data(hex: bundle.identity.publicKey)
        XCTAssertTrue(MimCore.verify(identityPublic: publicKey, payload: payload, signatureBase64: signature))
        XCTAssertFalse(
            MimCore.verify(
                identityPublic: publicKey,
                payload: Data("msg-1|\(tag)|alice|bob|device-8".utf8),
                signatureBase64: signature
            ),
            "改一个字节就必须验签失败"
        )
    }
}

extension Data {
    /// 十六进制串转 Data：测试里要喂 SPKI DER 给验签接口。
    init(hex: String) {
        var bytes = [UInt8]()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        self.init(bytes)
    }
}
