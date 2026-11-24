import Foundation

/// 把本机密钥包发布到服务端（M1 的「能被别人找到」这一步）。
///
/// 字段与桌面端 `KeyRegistry` 完全一致，服务端不需要为 iOS 做任何改动：
/// 1. `POST /device`        登记设备（设备名用于设备列表展示）
/// 2. `POST /key`           身份公钥（Base64 SPKI）+ 能力位；**必须带当前密码**
/// 3. `POST /prekey/signed` 签名预密钥 + 签名
/// 4. `POST /prekey/one-time` 批量一次性预密钥
///
/// 注意 `/key` 要求明文密码：这是服务端刻意的设计——只有被盗 JWT 不足以
/// 替换身份公钥。iOS 侧只在「首次发布」时调用，密码不落盘。
extension MIMClient {

    struct DeviceRegisterRequest: Encodable {
        let deviceId: String
        let deviceName: String
    }

    private struct KeyUploadRequest: Encodable {
        let publicKey: String
        let algorithm: String
        let password: String
        let supportsPadding: Bool
        let supportsRecallToken: Bool
        let supportsSenderCert: Bool
    }

    private struct SignedPreKeyUpload: Encodable {
        let preKeyId: UInt32
        let publicKey: String
        let signature: String
    }

    private struct OneTimePreKeyUpload: Encodable {
        let preKeyId: UInt32
        let publicKey: String

        init(id: UInt32, publicKey: String) {
            self.preKeyId = id
            self.publicKey = publicKey
        }
    }

    /// 登记本设备（`POST /device`）。幂等，每次登录都可以调。
    ///
    /// 单独抽出来是因为它和「发布公钥」是两件事：换了账号但公钥已发布过时，
    /// 设备登记仍然必须做——否则服务端会回「设备未注册或已注销」。
    /// 上报极光推送的 registrationId（App 重装会变，所以每次启动都报一次）。
    ///
    /// 服务端只记在这台设备名下：推送的目标是「设备」，不是「人」。
    public func reportPushRegistration(_ registrationId: String) async throws {
        struct Request: Encodable {
            let registrationId: String
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/device/push",
            body: Request(registrationId: registrationId)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "上报推送标识失败")
        }
    }

    public func registerDevice(deviceName: String) async throws {
        try await postVoid(
            path: "/device",
            body: DeviceRegisterRequest(deviceId: deviceId, deviceName: deviceName)
        )
    }

    /// 发布本机密钥包。幂等：同一把公钥重复上传服务端不会报错。
    public func publishKeys(
        password: String,
        deviceName: String,
        bundle: KeyBundle
    ) async throws {
        // 1) 设备登记：先登记再传钥，设备列表才会有这台机器。
        try await registerDevice(deviceName: deviceName)

        // 2) 身份公钥：Rust 里是 hex，服务端要 Base64(SPKI)。
        try await postVoid(
            path: "/key",
            body: KeyUploadRequest(
                publicKey: Data(hexString: bundle.identity.publicKey).base64EncodedString(),
                algorithm: "EC-P256",
                password: password,
                supportsPadding: true,
                supportsRecallToken: true,
                supportsSenderCert: true
            )
        )

        // 3) 签名预密钥：只传最新的一个，与服务端轮换语义一致。
        guard let signed = bundle.signedPreKeys.max(by: { $0.id < $1.id }) else {
            throw MIMApiError.failed(0, "密钥包缺少签名预密钥")
        }
        try await postVoid(
            path: "/prekey/signed",
            body: SignedPreKeyUpload(
                preKeyId: signed.id,
                publicKey: Data(hexString: signed.publicKey).base64EncodedString(),
                signature: signed.signature
            )
        )

        // 4) 一次性预密钥：批量上传，空包就跳过（没有一次性预密钥也能建会话）。
        if !bundle.oneTimePreKeys.isEmpty {
            let keys = bundle.oneTimePreKeys.map {
                OneTimePreKeyUpload(id: $0.id, publicKey: Data(hexString: $0.publicKey).base64EncodedString())
            }
            try await postVoid(path: "/prekey/one-time", body: keys)
        }
    }

    /// 只关心 status/message 的响应：`data` 可能是对象、数组、数字或 null，
    /// 用一个「什么都不解析」的类型吞掉，避免为了类型匹配而反复改结构体。
    struct IgnoredPayload: Decodable {
        init(from decoder: Decoder) throws {}
    }

    /// POST 且只关心成功/失败；服务端业务错误码要暴露出来，不能当成功。
    private func postVoid<B: Encodable>(path: String, body: B) async throws {
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: path, body: body
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "请求失败：\(path)")
        }
    }
}

extension Data {
    /// hex → Data。Rust 侧所有密钥都是十六进制文本，服务端要 Base64，这里做一次转换。
    public init(hexString: String) {
        var bytes = [UInt8]()
        bytes.reserveCapacity(hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2, limitedBy: hexString.endIndex) ?? hexString.endIndex
            bytes.append(UInt8(hexString[index..<next], radix: 16) ?? 0)
            index = next
        }
        self.init(bytes)
    }
}

extension Data {
    /// Data → 小写十六进制。Rust 侧的密钥参数都是 hex，服务端传的是 Base64，
    /// 两者之间必须显式转换——直接把 Base64 当 hex 传会得到 handshake 失败。
    public var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
