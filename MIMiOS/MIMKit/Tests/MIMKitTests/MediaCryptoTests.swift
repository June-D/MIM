import XCTest
@testable import MIMKit

/// 媒体加密：与桌面端共用 `mim_state_encrypt`，格式必须一致，否则换端打不开。
final class MediaCryptoTests: XCTestCase {

    func testRoundTripKeepsBytes() throws {
        let plain = Data((0..<4096).map { UInt8($0 % 251) })
        let encrypted = try MediaCrypto.encrypt(plain)

        // 三行文本格式：MIM-STATE-1 / Base64(nonce) / Base64(密文+tag)
        let text = String(decoding: encrypted.blob, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("MIM-STATE-1\n"))
        XCTAssertEqual(text.split(separator: "\n").count, 3)
        XCTAssertEqual(Data(base64Encoded: encrypted.keyBase64)?.count, 32)

        let decrypted = try MediaCrypto.decrypt(blob: encrypted.blob, keyBase64: encrypted.keyBase64)
        XCTAssertEqual(decrypted, plain)
    }

    func testWrongKeyFails() throws {
        let encrypted = try MediaCrypto.encrypt(Data("hello".utf8))
        let otherKey = Data(repeating: 7, count: 32).base64EncodedString()
        XCTAssertThrowsError(try MediaCrypto.decrypt(blob: encrypted.blob, keyBase64: otherKey))
    }

    func testTamperedCiphertextFails() throws {
        let encrypted = try MediaCrypto.encrypt(Data("hello".utf8))
        var text = String(decoding: encrypted.blob, as: UTF8.self)
        // 改掉密文行的最后一个字符：GCM 校验必须失败，不能悄悄返回错误明文。
        if let last = text.last, last != "\n" {
            text.removeLast()
            text.append(last == "A" ? "B" : "A")
        }
        XCTAssertThrowsError(
            try MediaCrypto.decrypt(blob: Data(text.utf8), keyBase64: encrypted.keyBase64)
        )
    }

    func testMediaPayloadMatchesDesktopShape() throws {
        // 字段名必须与桌面端 MediaPayload/MediaRefJson 一致，否则对方解不出附件。
        let payload = MediaPayload(
            kind: "image",
            text: "",
            media: MediaRef(
                id: "abc", key: "a2V5", size: 12, mime: "image/jpeg",
                name: "a.jpg", width: 10, height: 20, durationMs: nil
            )
        )
        let json = try JSONSerialization.jsonObject(
            with: try JSONEncoder().encode(payload)
        ) as? [String: Any]
        let dict = try XCTUnwrap(json)
        XCTAssertEqual(dict["kind"] as? String, "image")
        let media = try XCTUnwrap(dict["media"] as? [String: Any])
        XCTAssertEqual(Set(media.keys).isSuperset(of: ["id", "key", "size", "mime"]), true)
    }
}
