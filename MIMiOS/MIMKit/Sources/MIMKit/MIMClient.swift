import Foundation

/// 服务端统一响应体：`{status, message, data}`。
public struct MIMEnvelope<T: Decodable>: Decodable {
    public let status: Int
    public let message: String?
    public let data: T?
}

/// 业务错误。`status` 来自服务端，别和 HTTP 状态码混为一谈。
public enum MIMApiError: Error, Equatable {
    /// 需要二次验证码（服务端 40102）。
    case twoFactorRequired(String)
    /// 账号或密码错误（服务端 40001）。
    case invalidCredentials(String)
    case failed(Int, String)
    case transport(String)
}

public struct DeviceInfo: Decodable, Equatable, Sendable {
    public let deviceId: String
    public let deviceName: String?
    public let lastSeen: Int64?
    public let revoked: Bool?
}

struct DeviceListPayload: Decodable {
    let devices: [DeviceInfo]
}

/// 服务端 HTTP 客户端（M1 范围：登录 + 设备）。
///
/// 消息与媒体接口在 M2/M3 接入，复用同一个 `send`，错误处理只写一遍。
/// 令牌不加进 Keychain：由调用方决定存哪，客户端只持有内存副本。
public final class MIMClient: @unchecked Sendable {

    /// 调试/测试工具的默认锁定证书（命令行冒烟、集成脚本用）。
    ///
    /// 正式代码应显式传 `pinnedCertificate:`，别依赖这个全局值——
    /// 它存在的唯一理由是「命令行工具不想在每个构造点都传一遍证书」。
    public static var defaultPinnedCertificate: Data?

    private static let twoFactorRequired = 40102
    private static let invalidCredentials = 40001

    public let baseURL: URL
    public let deviceId: String
    private let session: URLSession
    /// 证书锁定器（只在自签环境下存在）；握手失败时它记着原因。
    private let pinner: PinnedSession?
    private(set) var token: String?
    /// 当前登录账号（登录成功后才有）。同步副本要靠它判断「这条是我发的还是对方发的」。
    public private(set) var username: String?

    /// 当前令牌（供 WebSocket 等复用；调用方不必自己再存一份）。
    public var currentToken: String? { token }

    /// - Parameter pinnedCertificate: 自签证书 DER。给了就只信它（调试期用），
    ///   不给则走系统默认校验（正式 CA 签发的证书）。
    public init(baseURL: URL, deviceId: String, token: String? = nil,
                pinnedCertificate: Data? = nil) {
        self.baseURL = baseURL
        self.deviceId = deviceId
        self.token = token
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 60
        let pinData = pinnedCertificate ?? Self.defaultPinnedCertificate
        if let pinData, let pinned = PinnedSession(certificateDER: pinData) {
            self.pinner = pinned
            self.session = pinned.makeSession(configuration: configuration)
        } else {
            self.pinner = nil
            self.session = URLSession(configuration: configuration)
        }
    }

    /// 最近一次 TLS 握手失败的原因（证书指纹不匹配等）。
    ///
    /// URLSession 在委托取消认证挑战时只会报 `cancelled`，
    /// 上层必须能拿到真正的原因，否则「登录失败」永远查不到点子上。
    public var lastTrustFailure: String? { pinner?.lastFailure }

    /// 传输层会话（WebSocket 复用同一个，避免两套证书校验逻辑）。
    public var transportSession: URLSession { session }

    /// 登录：`POST /login/token`，响应 `data` 是 JWT 字符串。
    @discardableResult
    public func login(username: String, password: String, code: String? = nil) async throws -> String {
        struct Request: Encodable {
            let username: String
            let password: String
            let code: String?
        }
        let envelope: MIMEnvelope<String> = try await send(
            method: "POST",
            path: "/login/token",
            body: Request(
                username: username,
                password: password,
                code: (code?.isEmpty ?? true) ? nil : code
            ),
            authenticated: false
        )
        switch envelope.status {
        case 200:
            guard let token = envelope.data, !token.isEmpty else {
                throw MIMApiError.failed(200, "登录未返回令牌")
            }
            self.token = token
            self.username = username
            return token
        case Self.twoFactorRequired:
            throw MIMApiError.twoFactorRequired(envelope.message ?? "请输入二次验证码或恢复码")
        case Self.invalidCredentials:
            throw MIMApiError.invalidCredentials(envelope.message ?? "账号或密码错误")
        default:
            throw MIMApiError.failed(envelope.status, envelope.message ?? "登录失败")
        }
    }

    /// 设备列表：`GET /device`。
    public func devices() async throws -> [DeviceInfo] {
        let envelope: MIMEnvelope<DeviceListPayload> = try await send(method: "GET", path: "/device")
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "查询设备失败")
        }
        return envelope.data?.devices ?? []
    }

    /// 注销某台设备：`DELETE /device/{id}`（服务端 DeviceController 的映射）。
    public func revokeDevice(_ id: String) async throws {
        let envelope: MIMEnvelope<Bool> = try await send(method: "DELETE", path: "/device/\(id)")
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "注销设备失败")
        }
    }

    /// 拼请求 URL。
    ///
    /// 不用 `appendingPathComponent`：它对 `/` 开头的路径会做转义，拼出
    /// `%2Flogin%2Ftoken` 这种「unsupported URL」，而且遇到尾斜杠的行为不直观。
    public func url(for path: String) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        // 必须把查询串单独放：整体塞进 `path` 会把 "?" 转义成 %3F，
        // 服务端收到的用户名变成 "xxx?deviceId=yyy"，然后报「用户名不合法」。
        let parts = path.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let rawPath = String(parts[0])
        components?.path = rawPath.hasPrefix("/") ? rawPath : "/" + rawPath
        if parts.count > 1 {
            components?.percentEncodedQuery = String(parts[1])
        }
        return components?.url ?? baseURL
    }

    // MARK: - 传输

    /// 无请求体（GET / DELETE）。
    func send<T: Decodable>(
        method: String,
        path: String,
        authenticated: Bool = true
    ) async throws -> MIMEnvelope<T> {
        try await perform(method: method, path: path, body: nil, authenticated: authenticated)
    }

    /// 有请求体（POST）：编码策略统一在这里，调用方不用关心 snake_case。
    ///
    /// 不用 `Optional<Never>` 当「无 body」哨兵：`Never` 的 Encodable 一致性
    /// 只有 macOS 14 / iOS 17 起才有，而我们最低支持 iOS 16。
    func send<T: Decodable, B: Encodable>(
        method: String,
        path: String,
        body: B,
        authenticated: Bool = true
    ) async throws -> MIMEnvelope<T> {
        // 服务端 DTO 是 camelCase（deviceId / publicKey / preKeyId），
        // 不能套 .convertToSnakeCase——那会把 deviceId 编成 device_id，
        // 服务端回「设备标识不能为空」。snake_case 只属于 Rust 那层。
        let encoder = JSONEncoder()
        return try await perform(
            method: method, path: path,
            body: try encoder.encode(body),
            authenticated: authenticated
        )
    }

    /// 原始请求（媒体二进制用它，不走 JSON 信封）。
    func rawSend(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch {
            throw MIMApiError.transport(error.localizedDescription)
        }
    }

    private func perform<T: Decodable>(
        method: String,
        path: String,
        body: Data?,
        authenticated: Bool
    ) async throws -> MIMEnvelope<T> {
        var request = URLRequest(url: url(for: path))
        request.httpMethod = method
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        if authenticated, let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw MIMApiError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw MIMApiError.transport("HTTP \(code)（\(method) \(path)）")
        }
        let decoder = JSONDecoder()
        // 响应是 camelCase；convertFromSnakeCase 对无下划线的键是恒等变换，
        // 保留它可以兼容将来出现的 snake_case 字段。
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let envelope: MIMEnvelope<T>
        do {
            envelope = try decoder.decode(MIMEnvelope<T>.self, from: data)
        } catch {
            throw MIMApiError.transport("响应不是预期的 JSON（\(method) \(path)）")
        }
        return envelope
    }
}
