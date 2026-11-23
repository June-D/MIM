import Foundation

extension MIMClient {

    /// 注册新账号（`POST /register/token`）。
    ///
    /// 协议版本必须与服务端常量一致，否则注册会被拒；这里与桌面端共用同一份
    /// `2026-12`，改协议文本时两处要一起改。
    public func register(username: String, password: String) async throws {
        struct Request: Encodable {
            let username: String
            let password: String
            let agreedTerms: Bool
            let termsVersion: String
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST",
            path: "/register/token",
            body: Request(
                username: username, password: password,
                agreedTerms: true, termsVersion: "2026-12"
            ),
            authenticated: false
        )
        // 已存在（40002 之类）不算致命：调用方随后直接登录即可。
        guard envelope.status == 200 || envelope.status == 40002 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "注册失败")
        }
    }
}
