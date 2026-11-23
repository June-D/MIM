import Foundation
import SwiftUI
import CryptoKit
import UIKit
import MIMKit

/// 界面状态与用例编排。
///
/// 刻意做得很薄：协议逻辑全在 MIMKit / Rust 里，这里只负责「调什么、界面显示什么」，
/// 与桌面端 AppStore 的分工一致。
@MainActor
final class AppModel: ObservableObject {

    /// 模拟器连本机；真机改成 https 地址（客户端会拒绝非回环明文）。
    @Published var serverURL = "http://127.0.0.1:8080"
    @Published var username = ""
    @Published var password = ""
    @Published var twoFactorCode = ""
    @Published var needsTwoFactor = false

    @Published private(set) var busy = false
    @Published private(set) var error: String?
    @Published private(set) var token: String?
    @Published private(set) var devices: [DeviceInfo] = []
    /// 本机身份公钥的短指纹，用于和历史同步确认弹窗里的值比对。
    @Published private(set) var identityFingerprint: String = ""

    /// 仅 DEBUG：用环境变量预填并自动登录，方便在模拟器里截图/联调。
    /// 用法：`SIMCTL_CHILD_MIM_DEMO_USER=... SIMCTL_CHILD_MIM_DEMO_PASSWORD=... `
    /// 发布版不会编译这段，也就不会出现「凭据能通过环境变量注入」的路径。
    #if DEBUG
    var shouldAutoLogin: Bool = false
    #endif

    private let keychain = Keychain()
    private var client: MIMClient?

    private static let identityAccount = "identity-bundle"
    private static let deviceIdAccount = "device-id"
    /// 「密钥已发布到服务端」的标记：换设备/换钥时才需要重新发布。
    private static let publishedAccount = "keys-published"

    /// 设备标识：iOS 没有 IMEI/序列号可用，这里生成一次并长期保存。
    /// 存 Keychain 而不是 UserDefaults：后者随备份迁移，会让两台设备撞 id。
    var deviceId: String {
        if let data = keychain.get(account: Self.deviceIdAccount),
           let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text
        }
        let generated = UUID().uuidString.lowercased()
        try? keychain.set(Data(generated.utf8), account: Self.deviceIdAccount)
        return generated
    }

    #if DEBUG
    /// 读环境变量做演示预填；真实使用永远走用户输入。
    func applyDemoPrefill() {
        let env = ProcessInfo.processInfo.environment
        if let server = env["MIM_DEMO_SERVER"], !server.isEmpty {
            serverURL = server
        }
        if let user = env["MIM_DEMO_USER"], !user.isEmpty {
            username = user
        }
        if let pass = env["MIM_DEMO_PASSWORD"], !pass.isEmpty {
            password = pass
        }
        shouldAutoLogin = !username.isEmpty && !password.isEmpty
    }
    #endif

    func login() async {
        error = nil
        guard let url = URL(string: serverURL), !username.isEmpty, !password.isEmpty else {
            error = "请填写服务端地址、用户名和密码"
            return
        }
        busy = true
        defer { busy = false }

        let client = MIMClient(baseURL: url, deviceId: deviceId)
        do {
            let token = try await client.login(
                username: username,
                password: password,
                code: needsTwoFactor ? twoFactorCode : nil
            )
            self.client = client
            self.token = token
            let bundle = try ensureIdentityBundle()
            try await publishIfNeeded(client: client, bundle: bundle)
            await loadDevices()
        } catch MIMApiError.twoFactorRequired(let message) {
            // 服务端要求二次验证：留在登录页，把输入框露出来。
            needsTwoFactor = true
            error = message
        } catch MIMApiError.invalidCredentials(let message) {
            error = message
        } catch {
            self.error = "\(error)"
        }
    }

    func loadDevices() async {
        guard let client else { return }
        do {
            devices = try await client.devices()
        } catch {
            self.error = "\(error)"
        }
    }

    func logout() {
        token = nil
        client = nil
        devices = []
        password = ""
        twoFactorCode = ""
        needsTwoFactor = false
    }

    /// 首次登录时把公钥发布到服务端，之后不再重复。
    ///
    /// 不发布的话，别人查不到这台设备的预密钥，就永远无法给它发起会话——
    /// 登录成功但「没人能给你发消息」是 iOS 侧最容易漏掉的一步。
    /// 注意 `/key` 要求当前密码：只有被盗 JWT 不足以替换身份公钥。
    private func publishIfNeeded(client: MIMClient, bundle: KeyBundle) async throws {
        guard keychain.get(account: Self.publishedAccount) == nil else {
            return
        }
        try await client.publishKeys(
            password: password,
            deviceName: UIDevice.current.name,
            bundle: bundle
        )
        try? keychain.set(Data("1".utf8), account: Self.publishedAccount)
    }

    /// 本机身份密钥包：首次登录生成并存 Keychain，之后只读取。
    ///
    /// 私钥只在这里出现，且只写 Keychain——不上传、不落 UserDefaults、不进日志。
    @discardableResult
    private func ensureIdentityBundle() throws -> KeyBundle {
        var bundle: KeyBundle
        if let data = keychain.get(account: Self.identityAccount),
           let stored = try? JSONDecoder().decode(KeyBundle.self, from: data) {
            bundle = stored
        } else {
            bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 20)
            let data = try JSONEncoder().encode(bundle)
            try keychain.set(data, account: Self.identityAccount)
        }
        identityFingerprint = Self.fingerprint(ofHex: bundle.identity.publicKey)
        return bundle
    }

    /// SPKI DER 的 SHA-256 前 128 位，4 位分组——与桌面端 DeviceFingerprint 同一算法。
    private static func fingerprint(ofHex hex: String) -> String {
        var bytes = [UInt8]()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        let digest = SHA256.hash(data: Data(bytes))
        return digest.prefix(8).map { String(format: "%02X", $0) }.joined()
            .chunked(every: 4).joined(separator: " ")
    }
}

private extension String {
    func chunked(every size: Int) -> [String] {
        var chunks: [String] = []
        var current = startIndex
        while current < endIndex {
            let next = index(current, offsetBy: size, limitedBy: endIndex) ?? endIndex
            chunks.append(String(self[current..<next]))
            current = next
        }
        return chunks
    }
}
