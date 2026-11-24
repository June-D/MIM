import XCTest
@testable import MIMKit

/// 同步副本的验签逻辑。
///
/// 这条路径要真机端到端验证成本很高（需要另一台有历史的设备在线转发），
/// 所以把「规范串 + 验签」抽成纯函数，用单测钉住格式与失败条件。
final class SyncPayloadTests: XCTestCase {

    private let json = """
    {"v":1,"kind":"sync","clientMsgId":"msg-1","tag":"tagAAAA","from":"alice",
     "to":"bob","device":"desktop-1","clientTime":1795000000000,
     "text":"历史里的一条","media":null}
    """

    private func payload() throws -> SyncPayload {
        try JSONDecoder().decode(SyncPayload.self, from: Data(json.utf8))
    }

    func testCanonicalMatchesDesktop() throws {
        // 桌面端定义：clientMsgId|tag|from|to|device —— 少一段、换顺序都会验签失败。
        XCTAssertEqual(
            try payload().canonical,
            "msg-1|tagAAAA|alice|bob|desktop-1"
        )
    }

    func testValidSignaturePassesAndTamperingFails() throws {
        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 0)
        let publicDER = Data(hexString: bundle.identity.publicKey)
        let signed = try payload()
        let signature = try MimCore.sign(
            identityPrivateHex: bundle.identity.privateKey,
            payload: Data(signed.canonical.utf8)
        )
        XCTAssertTrue(signed.verify(signatureBase64: signature, sourcePublicKeyDER: publicDER))

        // 改一个字段（模拟服务端/中途被改过）必须验签失败。
        var tampered = try payload()
        tampered = SyncPayload(
            v: tampered.v, kind: tampered.kind, clientMsgId: tampered.clientMsgId,
            tag: tampered.tag, from: "mallory", to: tampered.to,
            device: tampered.device, clientTime: tampered.clientTime,
            text: tampered.text, media: tampered.media
        )
        XCTAssertFalse(tampered.verify(signatureBase64: signature, sourcePublicKeyDER: publicDER))
    }

    func testWrongKeyFails() throws {
        let signer = try MimCore.makeKeyBundle(oneTimePreKeys: 0)
        let other = try MimCore.makeKeyBundle(oneTimePreKeys: 0)
        let signed = try payload()
        let signature = try MimCore.sign(
            identityPrivateHex: signer.identity.privateKey,
            payload: Data(signed.canonical.utf8)
        )
        XCTAssertFalse(
            signed.verify(
                signatureBase64: signature,
                sourcePublicKeyDER: Data(hexString: other.identity.publicKey)
            )
        )
    }
}
