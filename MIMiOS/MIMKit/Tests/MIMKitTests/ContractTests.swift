import XCTest
@testable import MIMKit

/// 钉住与 Rust 之间的 JSON 契约。
///
/// 谁改了 `src/messaging.rs` 的字段名，这些用例先红——比联调时只看到「服务端 400」便宜得多。
final class ContractTests: XCTestCase {

    func testComposeRequestUsesRustFieldNames() throws {
        let request = ComposeFirstRequest(
            identityPrivate: "aa",
            identityPublic: "bb",
            peerIdentityPublic: "cc",
            peerSignedPreKeyId: 7,
            peerSignedPreKeyPublic: "dd",
            peerOneTimePreKeyId: nil,
            peerOneTimePreKeyPublic: nil,
            plaintext: "hi",
            conversationTag: "tag",
            sessionId: "sid",
            certificate: SenderCertificate(
                username: "alice", deviceId: "d1", identityKey: "kk",
                issuedAt: 1, expiresAt: 2, keyId: "00", signature: "ss"
            )
        )
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let raw = try encoder.encode(request)
        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: raw) as? [String: Any]
        )

        let keys = Set(json.keys)
        XCTAssertTrue(keys.contains("identity_private"))
        XCTAssertTrue(keys.contains("peer_signed_pre_key_id"))
        XCTAssertTrue(keys.contains("conversation_tag"))
        XCTAssertTrue(keys.contains("session_id"))

        let certificate = try XCTUnwrap(json["certificate"] as? [String: Any])
        XCTAssertTrue(certificate.keys.contains("device_id"), "证书里的 deviceId 必须编成 device_id")
        XCTAssertTrue(certificate.keys.contains("identity_key"))
    }

    func testKeyBundleDecodesSnakeCaseFromCore() throws {
        // 真实来自 Rust 的输出（signed_pre_keys / one_time_pre_keys 都是 snake_case）。
        let json = """
        {
          "version": 1,
          "identity": {"private_key": "aa", "public_key": "bb"},
          "signed_pre_keys": [
            {"id": 1, "private_key": "p", "public_key": "q", "signature": "s", "created_at": 10}
          ],
          "one_time_pre_keys": [{"id": 2, "private_key": "p2", "public_key": "q2"}],
          "next_one_time_pre_key_id": 3
        }
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let bundle = try decoder.decode(KeyBundle.self, from: Data(json.utf8))

        XCTAssertEqual(bundle.identity.publicKey, "bb")
        XCTAssertEqual(bundle.signedPreKeys.first?.signature, "s")
        XCTAssertEqual(bundle.oneTimePreKeys.first?.id, 2)
        XCTAssertEqual(bundle.nextOneTimePreKeyId, 3)
    }

    func testEnvelopeDecodesStatusAndData() throws {
        let json = #"{"status":40102,"message":"请输入二次验证码","data":null,"rel":true}"#
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let envelope = try decoder.decode(MIMEnvelope<String>.self, from: Data(json.utf8))

        XCTAssertEqual(envelope.status, 40102)
        XCTAssertEqual(envelope.message, "请输入二次验证码")
        XCTAssertNil(envelope.data)
    }
}
