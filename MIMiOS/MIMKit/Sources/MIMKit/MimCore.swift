import Foundation
import MIMCoreABI

/// C ABI 的错误码。参数错误与密码学失败必须分开——
/// 「密文被篡改」和「JSON 拼错了」是两种完全不同的 bug。
public enum MIMCoreError: Error, Equatable {
    case invalidArgument(String)
    case handshake
    case encrypt
    case decrypt
    case state
    case unknown(Int32)

    static func make(_ code: Int32, _ context: String) -> MIMCoreError? {
        switch code {
        case 0: return nil
        case 1: return .invalidArgument(context)
        case 2: return .handshake
        case 3: return .encrypt
        case 4: return .decrypt
        case 5: return .state
        default: return .unknown(code)
        }
    }
}

/// 薄封装：只负责 Swift ↔ C 的编解码与缓冲交接，协议逻辑一律在 Rust 里。
///
/// 输出缓冲由库分配，**必须**用 `mim_bytes_free` 归还；这里把「读 + 归还」
/// 收敛到唯一出口，避免某条分支忘记 free。
enum FFI {

    static func call(
        _ context: String,
        _ input: Data = Data(),
        _ body: (UnsafePointer<UInt8>?, Int,
                 UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
                 UnsafeMutablePointer<Int>?) -> Int32
    ) throws -> Data {
        var out: UnsafeMutablePointer<UInt8>?
        var outLen = 0
        let code: Int32 = input.withUnsafeBytes { raw in
            body(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &out, &outLen)
        }
        defer { mim_bytes_free(out, outLen) }
        if let error = MIMCoreError.make(code, context) {
            throw error
        }
        guard let out, outLen > 0 else { return Data() }
        return Data(bytes: out, count: outLen)
    }
}

public enum MimCore {

    /// JSON 字段一律 snake_case：Rust 侧 serde 的默认命名。
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    public static var version: String {
        String(cString: mim_version())
    }

    /// 生成密钥包（身份 + 签名预密钥 + 一次性预密钥）。
    public static func makeKeyBundle(version: UInt32 = 1, oneTimePreKeys: UInt32 = 20) throws -> KeyBundle {
        var out: UnsafeMutablePointer<UInt8>?
        var outLen = 0
        let code = mim_key_bundle_generate(version, oneTimePreKeys, &out, &outLen)
        defer { mim_bytes_free(out, outLen) }
        if let error = MIMCoreError.make(code, "生成密钥包") {
            throw error
        }
        guard let out else { throw MIMCoreError.invalidArgument("密钥包为空") }
        return try decoder.decode(KeyBundle.self, from: Data(bytes: out, count: outLen))
    }

    /// 组装首条消息（X3DH + 证书信封 + 双棘轮）。
    public static func composeFirstMessage(_ request: ComposeFirstRequest) throws -> ComposeFirstResponse {
        let payload = try encoder.encode(request)
        let data = try FFI.call("组装首条消息", payload, mim_compose_first_message)
        return try decoder.decode(ComposeFirstResponse.self, from: data)
    }

    /// 拆开对端的首条消息，拿回明文与会话状态。
    public static func openFirstMessage(_ request: OpenFirstRequest) throws -> OpenFirstResponse {
        let payload = try encoder.encode(request)
        let data = try FFI.call("拆首条消息", payload, mim_open_first_message)
        return try decoder.decode(OpenFirstResponse.self, from: data)
    }

    /// 用身份私钥签名（多设备同步副本的归属）。返回 Base64 文本。
    ///
    /// 两个输入 + 输出缓冲，超出了 FFI.call 的单输入约定，这里直接绑定。
    public static func sign(identityPrivateHex: String, payload: Data) throws -> String {
        let privateBytes = Data(identityPrivateHex.utf8)
        var out: UnsafeMutablePointer<UInt8>?
        var outLen = 0
        let code: Int32 = privateBytes.withUnsafeBytes { privateRaw in
            payload.withUnsafeBytes { payloadRaw in
                mim_sign_with_identity(
                    privateRaw.bindMemory(to: UInt8.self).baseAddress, privateRaw.count,
                    payloadRaw.bindMemory(to: UInt8.self).baseAddress, payloadRaw.count,
                    &out, &outLen
                )
            }
        }
        defer { mim_bytes_free(out, outLen) }
        if let error = MIMCoreError.make(code, "签名") {
            throw error
        }
        guard let out, outLen > 0 else {
            throw MIMCoreError.invalidArgument("签名结果为空")
        }
        return String(decoding: Data(bytes: out, count: outLen), as: UTF8.self)
    }

    /// 验证身份签名。任何解析失败都算失败，不区分「格式错」与「签名错」。
    public static func verify(identityPublic: Data, payload: Data, signatureBase64: String) -> Bool {
        let signature = Data(signatureBase64.utf8)
        let code: Int32 = identityPublic.withUnsafeBytes { publicRaw in
            payload.withUnsafeBytes { payloadRaw in
                signature.withUnsafeBytes { signatureRaw in
                    mim_verify_with_identity(
                        publicRaw.bindMemory(to: UInt8.self).baseAddress, publicRaw.count,
                        payloadRaw.bindMemory(to: UInt8.self).baseAddress, payloadRaw.count,
                        signatureRaw.bindMemory(to: UInt8.self).baseAddress, signatureRaw.count
                    )
                }
            }
        }
        return code == 0
    }
}
