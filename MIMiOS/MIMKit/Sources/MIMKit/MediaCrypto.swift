import Foundation
import MIMCoreABI

/// 媒体加密载荷：与桌面端的 `MediaPayload` 逐字对应。
///
/// 服务端只存密文（`POST /media` 收 octet-stream），密钥在这里随消息密文一起走，
/// 所以服务端既解不开媒体，也看不到文件名。
public struct MediaRef: Codable, Equatable, Sendable {
    public let id: String
    /// Base64 的 32 字节一次性密钥。
    public let key: String
    public let size: Int
    public let mime: String
    public let name: String?
    public let width: Int?
    public let height: Int?
    public let durationMs: Int64?

    public init(id: String, key: String, size: Int, mime: String, name: String?,
                width: Int?, height: Int?, durationMs: Int64?) {
        self.id = id
        self.key = key
        self.size = size
        self.mime = mime
        self.name = name
        self.width = width
        self.height = height
        self.durationMs = durationMs
    }
}

public struct MediaPayload: Codable, Equatable, Sendable {
    public let v: Int
    public let kind: String
    public let text: String
    public let media: MediaRef

    public init(kind: String, text: String, media: MediaRef) {
        self.v = 1
        self.kind = kind
        self.text = text
        self.media = media
    }
}

/// 媒体加密/解密。
///
/// 复用 `mim_state_encrypt`（AES-256-GCM，三行文本格式）：与桌面端一致，
/// 换了客户端也解不开格式——但两端都按同一份 ABI 走，所以互认。
public enum MediaCrypto {

    public struct Encrypted {
        public let blob: Data
        /// Base64 的 32 字节密钥，随消息密文发给对端。
        public let keyBase64: String
    }

    public static func encrypt(_ plaintext: Data) throws -> Encrypted {
        var key = Data(count: 32)
        var nonce = Data(count: 12)
        let keyStatus = key.withUnsafeMutableBytes { buffer -> Int32 in
            mim_random_bytes(buffer.bindMemory(to: UInt8.self).baseAddress, 32)
        }
        let nonceStatus = nonce.withUnsafeMutableBytes { buffer -> Int32 in
            mim_random_bytes(buffer.bindMemory(to: UInt8.self).baseAddress, 12)
        }
        guard keyStatus == 0, nonceStatus == 0 else { throw MIMCoreError.state }

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
        guard code == 0, let out else { throw MIMCoreError.encrypt }
        return Encrypted(
            blob: Data(bytes: out, count: outLen),
            keyBase64: key.base64EncodedString()
        )
    }

    public static func decrypt(blob: Data, keyBase64: String) throws -> Data {
        guard let key = Data(base64Encoded: keyBase64), key.count == 32 else {
            throw MIMCoreError.invalidArgument("媒体密钥不合法")
        }
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
        guard code == 0, let out else { throw MIMCoreError.decrypt }
        return Data(bytes: out, count: outLen)
    }
}
