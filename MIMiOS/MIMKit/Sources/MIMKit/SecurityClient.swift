import Foundation

/// 账号安全：修改密码、二次验证（TOTP）、恢复码。与桌面端同一套接口。
public struct TwoFactorStatus: Decodable, Sendable {
    public let enabled: Bool
}

public struct TwoFactorSetup: Decodable, Sendable {
    /// Base32 密钥，供手动输入或生成二维码。
    public let secret: String
    /// `otpauth://` 链接，可直接渲染成二维码。
    public let otpauthUri: String?
}

public struct TwoFactorEnabled: Decodable, Sendable {
    public let token: String?
    public let recoveryCodes: [String]?
}

extension MIMClient {

    public func twoFactorStatus() async throws -> Bool {
        struct Body: Decodable { let enabled: Bool }
        let envelope: MIMEnvelope<Body> = try await send(method: "GET", path: "/user/2fa/status")
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "查询二次验证失败")
        }
        return envelope.data?.enabled ?? false
    }

    /// 生成新的 TOTP 密钥（尚未启用，需再用验证码确认）。
    public func setupTwoFactor() async throws -> TwoFactorSetup {
        let envelope: MIMEnvelope<TwoFactorSetup> = try await send(
            method: "POST", path: "/user/2fa/setup"
        )
        guard envelope.status == 200, let data = envelope.data else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "生成二次验证密钥失败")
        }
        return data
    }

    /// 用一次验证码确认启用；返回恢复码（只显示这一次）。
    public func enableTwoFactor(code: String) async throws -> TwoFactorEnabled {
        struct Request: Encodable { let code: String }
        let envelope: MIMEnvelope<TwoFactorEnabled> = try await send(
            method: "POST", path: "/user/2fa/enable", body: Request(code: code)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "启用二次验证失败")
        }
        return envelope.data ?? TwoFactorEnabled(token: nil, recoveryCodes: [])
    }

    /// 关闭二次验证：需要当前密码 + 一个有效验证码。
    public func disableTwoFactor(password: String, code: String) async throws {
        struct Request: Encodable {
            let password: String
            let code: String
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/user/2fa/disable",
            body: Request(password: password, code: code)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "关闭二次验证失败")
        }
    }

    /// 重新生成恢复码（旧码全部作废）。
    public func regenerateRecoveryCodes(password: String, code: String) async throws -> [String] {
        struct Request: Encodable {
            let password: String
            let code: String
        }
        struct Body: Decodable { let recoveryCodes: [String]? }
        let envelope: MIMEnvelope<Body> = try await send(
            method: "POST", path: "/user/2fa/recovery-codes",
            body: Request(password: password, code: code)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "生成恢复码失败")
        }
        return envelope.data?.recoveryCodes ?? []
    }

    /// 修改密码。服务端会签发新令牌（旧令牌全部失效）。
    @discardableResult
    public func changePassword(old: String, new: String) async throws -> String? {
        struct Request: Encodable {
            let oldPassword: String
            let newPassword: String
        }
        struct Body: Decodable { let token: String? }
        let envelope: MIMEnvelope<Body> = try await send(
            method: "POST", path: "/user/password",
            body: Request(oldPassword: old, newPassword: new)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "修改密码失败")
        }
        return envelope.data?.token
    }
}
