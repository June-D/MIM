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

    private static let twoFactorRequired = 40102
    private static let invalidCredentials = 40001

    public let baseURL: URL
    public let deviceId: String
    private let session: URLSession
    private(set) var token: String?

    public init(baseURL: URL, deviceId: String, token: String? = nil) {
        self.baseURL = baseURL
        self.deviceId = deviceId
        self.token = token
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: configuration)
    }

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

    /// 注销某台设备：`DELETE /user/device/{id}`。
    public func revokeDevice(_ id: String) async throws {
        let envelope: MIMEnvelope<Bool> = try await send(method: "DELETE", path: "/user/device/\(id)")
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "注销设备失败")
        }
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
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return try await perform(
            method: method, path: path,
            body: try encoder.encode(body),
            authenticated: authenticated
        )
    }

    private func perform<T: Decodable>(
        method: String,
        path: String,
        body: Data?,
        authenticated: Bool
    ) async throws -> MIMEnvelope<T> {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
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
            throw MIMApiError.transport("HTTP 状态异常")
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        do {
            return try decoder.decode(MIMEnvelope<T>.self, from: data)
        } catch {
            throw MIMApiError.transport("响应不是预期的 JSON")
        }
    }
}
