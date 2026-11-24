import Foundation

/// 找回邮箱与邮箱找回密码。
///
/// 服务端早就实现了这套流程（`RecoveryController`），缺的一直是「有邮件通道」和
/// 「客户端界面」——现在两样都有：阿里云邮件推送（SMTP）+ 这里的接口封装。
extension MIMClient {

    /// 账号资料（含解密后的找回邮箱）。
    public struct Profile: Decodable, Sendable {
        public let username: String?
        public let email: String?
        public let nickname: String?
    }

    public func profile() async throws -> Profile {
        let envelope: MIMEnvelope<Profile> = try await send(method: "GET", path: "/user/info")
        guard envelope.status == 200, let data = envelope.data else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "读取账号资料失败")
        }
        return data
    }

    /// 绑定 / 修改找回邮箱。**必须带当前密码**——令牌泄漏不足以改走找回通道。
    public func bindEmail(password: String, email: String) async throws {
        struct Request: Encodable {
            let password: String
            let email: String
        }
        let envelope: MIMEnvelope<Bool> = try await send(
            method: "POST", path: "/user/email", body: Request(password: password, email: email)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "绑定邮箱失败")
        }
    }

    /// 发送找回密码的验证码（发到该账号**已绑定**的邮箱）。
    ///
    /// 未登录也能调：忘记密码的人正是没有令牌的人。
    public func requestRecoveryCode(username: String) async throws {
        struct Request: Encodable {
            let username: String
        }
        let envelope: MIMEnvelope<Bool> = try await send(
            method: "POST", path: "/recover/email", body: Request(username: username),
            authenticated: false
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "发送验证码失败")
        }
    }

    /// 用邮箱验证码重置密码。成功后服务端会让该账号所有设备下线。
    public func resetPassword(
        username: String, code: String, newPassword: String
    ) async throws {
        struct Request: Encodable {
            let username: String
            let code: String
            let newPassword: String
        }
        let envelope: MIMEnvelope<Bool> = try await send(
            method: "POST", path: "/recover/reset",
            body: Request(username: username, code: code, newPassword: newPassword),
            authenticated: false
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "重置密码失败")
        }
    }
}
