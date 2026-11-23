import Foundation

// 目的：验证「Swift 调 C ABI」这一层的写法，而不是验证密码学（那由 Rust 测试覆盖）。
//
// 这段代码在 macOS 上跑，和 iOS 上几乎一字不差——头文件导入、指针传递、
// 缓冲归还、JSON 字段名，都是 iOS 侧真正会踩的坑。装好 Xcode 后可以直接搬进工程。
//
// C ABI 见 include/mim_core.h；错误码定义在头文件里，
// 这里用字面量并在注释里标注，避免 Swift 对宏的导入类型在不同版本上不一致。

let MIM_OK: Int32 = 0
let MIM_ERR_INVALID_ARG: Int32 = 1
let MIM_ERR_STATE: Int32 = 5

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("✗ \(message)\n".utf8))
    exit(1)
}

func note(_ message: String) {
    print("· \(message)")
}

/// 把库分配的缓冲读成 Data 并立即归还；漏掉 free 就是内存泄漏，这里刻意做成唯一出口。
func take(_ pointer: UnsafeMutablePointer<UInt8>?, _ length: Int) -> Data {
    defer { mim_bytes_free(pointer, length) }
    guard let pointer, length > 0 else { return Data() }
    return Data(bytes: pointer, count: length)
}

func hexToData(_ hex: String) -> Data {
    var data = Data(capacity: hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        guard let byte = UInt8(hex[index..<next], radix: 16) else { fail("非法十六进制：\(hex)") }
        data.append(byte)
        index = next
    }
    return data
}

/// 调 C ABI 的通用壳：输入 Data，返回 (错误码, 输出 Data)。
func invoke(_ body: (UnsafePointer<UInt8>?, Int, UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?, UnsafeMutablePointer<Int>?) -> Int32,
            _ input: Data) -> (Int32, Data) {
    var out: UnsafeMutablePointer<UInt8>? = nil
    var outLen = 0
    let code: Int32 = input.withUnsafeBytes { raw in
        body(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &out, &outLen)
    }
    return (code, take(out, outLen))
}

// MARK: - 1. 生成两份密钥包（ABI 的第一个调用）

func generateBundle(_ label: String) -> [String: Any] {
    var out: UnsafeMutablePointer<UInt8>? = nil
    var outLen = 0
    let code = mim_key_bundle_generate(1, 1, &out, &outLen)
    guard code == MIM_OK else { fail("\(label) 密钥包生成失败：code=\(code)") }
    let data = take(out, outLen)
    guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        fail("\(label) 密钥包不是合法 JSON")
    }
    return json
}

let alice = generateBundle("alice")
let bob = generateBundle("bob")
note("密钥包已生成（各 1 个一次性预密钥）")

guard let aliceIdentity = alice["identity"] as? [String: Any],
      let bobIdentity = bob["identity"] as? [String: Any],
      let alicePrivate = aliceIdentity["private_key"] as? String,
      let alicePublic = aliceIdentity["public_key"] as? String,
      let bobPublic = bobIdentity["public_key"] as? String,
      let bobSigned = (bob["signed_pre_keys"] as? [[String: Any]])?.first,
      let bobOneTime = (bob["one_time_pre_keys"] as? [[String: Any]])?.first
else { fail("密钥包字段缺失，检查 keys.rs 的 to_json") }

// MARK: - 2. 组装首条消息

let tag = "gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E"
let certificate: [String: Any] = [
    "username": "alice",
    "device_id": "alice-iphone",
    "identity_key": Data(hexToData(alicePublic)).base64EncodedString(),
    "issued_at": 1_795_056_000_000,
    "expires_at": 1_795_142_400_000,
    "key_id": String(repeating: "00", count: 32),
    "signature": "c2lnbmF0dXJl",
]

let composeRequest: [String: Any] = [
    "identity_private": alicePrivate,
    "identity_public": alicePublic,
    "peer_identity_public": bobPublic,
    "peer_signed_pre_key_id": bobSigned["id"] as Any,
    "peer_signed_pre_key_public": bobSigned["public_key"] as Any,
    "peer_one_time_pre_key_id": bobOneTime["id"] as Any,
    "peer_one_time_pre_key_public": bobOneTime["public_key"] as Any,
    "plaintext": "来自 Swift 的首条消息",
    "conversation_tag": tag,
    "session_id": "session-swift",
    "certificate": certificate,
]

let composed = invoke(mim_compose_first_message, try! JSONSerialization.data(withJSONObject: composeRequest))
guard composed.0 == MIM_OK else { fail("组装首条消息失败：code=\(composed.0)") }
guard let composedJson = try? JSONSerialization.jsonObject(with: composed.1) as? [String: Any] else {
    fail("首条消息响应不是合法 JSON")
}
note("首条消息已组装：tag=\(composedJson["conversation_tag"] as? String ?? "?")")

// MARK: - 3. 对端拆开首条消息

let openRequest: [String: Any] = [
    "identity_private": bobIdentity["private_key"] as Any,
    "identity_public": bobPublic,
    "signed_pre_key_private": bobSigned["private_key"] as Any,
    "signed_pre_key_public": bobSigned["public_key"] as Any,
    "one_time_pre_key_private": bobOneTime["private_key"] as Any,
    "peer_identity_public": composedJson["sender_identity_key"] as Any,
    "peer_ephemeral_public": composedJson["sender_ephemeral_key"] as Any,
    "used_one_time_pre_key": true,
    "wire": composedJson["wire"] as Any,
]

let opened = invoke(mim_open_first_message, try! JSONSerialization.data(withJSONObject: openRequest))
guard opened.0 == MIM_OK else { fail("拆首条消息失败：code=\(opened.0)") }
guard let openedJson = try? JSONSerialization.jsonObject(with: opened.1) as? [String: Any],
      let plaintext = openedJson["plaintext"] as? String
else { fail("拆首条消息响应缺 plaintext") }
guard plaintext == "来自 Swift 的首条消息" else { fail("明文不一致：\(plaintext)") }
note("对端解出明文：\(plaintext)")

// MARK: - 4. 身份签名（多设备同步副本归属）

let payload = Data("msg-1|\(tag)|alice|bob|device-9".utf8)
var signatureOut: UnsafeMutablePointer<UInt8>? = nil
var signatureLen = 0
let signCode = alicePrivate.withCString { privatePtr in
    payload.withUnsafeBytes { raw in
        mim_sign_with_identity(
            UnsafeRawPointer(privatePtr).assumingMemoryBound(to: UInt8.self), strlen(privatePtr),
            raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
            &signatureOut, &signatureLen
        )
    }
}
guard signCode == MIM_OK else { fail("签名失败：code=\(signCode)") }
let signature = take(signatureOut, signatureLen)
note("签名长度 \(signature.count) 字节（Base64 文本）")

let publicBytes = hexToData(alicePublic)
let verified = publicBytes.withUnsafeBytes { pub in
    payload.withUnsafeBytes { raw in
        signature.withUnsafeBytes { sig in
            mim_verify_with_identity(
                pub.bindMemory(to: UInt8.self).baseAddress, pub.count,
                raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
                sig.bindMemory(to: UInt8.self).baseAddress, sig.count
            )
        }
    }
}
guard verified == MIM_OK else { fail("验签应通过，实际 code=\(verified)") }

let tampered = Data("msg-1|\(tag)|alice|bob|device-8".utf8)
let rejected = publicBytes.withUnsafeBytes { pub in
    tampered.withUnsafeBytes { raw in
        signature.withUnsafeBytes { sig in
            mim_verify_with_identity(
                pub.bindMemory(to: UInt8.self).baseAddress, pub.count,
                raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
                sig.bindMemory(to: UInt8.self).baseAddress, sig.count
            )
        }
    }
}
guard rejected == MIM_ERR_STATE else { fail("篡改后应返回 MIM_ERR_STATE(5)，实际 \(rejected)") }
note("签名往返正常，篡改被拒")

// MARK: - 5. 非法输入不能崩

let junk = Data("not json".utf8)
let bad = invoke(mim_compose_first_message, junk)
guard bad.0 == MIM_ERR_INVALID_ARG else { fail("坏 JSON 应返回 MIM_ERR_INVALID_ARG(1)，实际 \(bad.0)") }
note("非法输入返回错误码而非崩溃")

print("SWIFT_ABI_OK")
