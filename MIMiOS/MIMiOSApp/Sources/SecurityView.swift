import SwiftUI
import MIMKit

/// 账号安全：修改密码 + 二次验证（TOTP）+ 恢复码。
///
/// 对应桌面端的「安全」页。移动端没有二维码扫描依赖，用「复制密钥」+
/// 手动输入的方式接入验证器——和桌面端显示的 secret 是同一个。
struct SecurityView: View {
    @EnvironmentObject private var model: AppModel

    @State private var twoFactorEnabled = false
    @State private var loading = true

    // 修改密码
    @State private var oldPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""

    // 启用 2FA
    @State private var setup: MIMKit.TwoFactorSetup?
    @State private var setupCode = ""
    @State private var recoveryCodes: [String] = []

    // 关闭 2FA
    @State private var disablePassword = ""
    @State private var disableCode = ""

    // 找回邮箱
    @State private var emailPassword = ""
    @State private var emailInput = ""

    @State private var busy = false

    var body: some View {
        List {
            Section {
                LabeledContent("二次验证") {
                    Text(twoFactorEnabled ? "已启用" : "未启用")
                        .foregroundStyle(twoFactorEnabled ? .green : .secondary)
                }
                Text("启用后登录需要额外输入验证器上的 6 位码。恢复码请离线保存，"
                     + "它是验证器丢失时唯一的登录方式。")
                    .font(.caption2).foregroundStyle(.secondary)
            } header: {
                Text("二次验证")
            } footer: {
                if loading { ProgressView() }
            }

            if twoFactorEnabled {
                Section("关闭二次验证") {
                    SecureField("当前密码", text: $disablePassword)
                    TextField("6 位验证码", text: $disableCode)
                        .keyboardType(.numberPad)
                    Button("关闭", role: .destructive) {
                        Task { await disableTwoFactor() }
                    }
                    .disabled(busy || disablePassword.isEmpty || disableCode.count != 6)
                }
            } else if let setup {
                Section("1. 在验证器里添加密钥") {
                    Text(setup.secret)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                    Button {
                        UIPasteboard.general.string = setup.secret
                    } label: {
                        Label("复制密钥", systemImage: "doc.on.doc")
                    }
                }
                Section("2. 输入验证器上的 6 位码") {
                    TextField("6 位验证码", text: $setupCode)
                        .keyboardType(.numberPad)
                    Button("启用") { Task { await enableTwoFactor() } }
                        .disabled(busy || setupCode.count != 6)
                }
            } else {
                Section {
                    Button("生成二次验证密钥") { Task { await loadSetup() } }
                        .disabled(busy)
                }
            }

            if !recoveryCodes.isEmpty {
                Section {
                    ForEach(recoveryCodes, id: \.self) { code in
                        Text(code).font(.body.monospaced()).textSelection(.enabled)
                    }
                    Button {
                        UIPasteboard.general.string = recoveryCodes.joined(separator: "\n")
                    } label: {
                        Label("复制全部恢复码", systemImage: "doc.on.doc")
                    }
                } header: {
                    Text("恢复码（只显示这一次）")
                } footer: {
                    Text("每个恢复码只能用一次。请保存到密码管理器或打印出来。")
                }
            }

            Section {
                LabeledContent("找回邮箱") {
                    Text(model.boundEmail ?? "未绑定")
                        .foregroundStyle(model.boundEmail == nil ? .secondary : .primary)
                }
                SecureField("当前密码", text: $emailPassword)
                TextField("邮箱地址", text: $emailInput)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button(model.boundEmail == nil ? "绑定邮箱" : "更换邮箱") {
                    Task { await bindEmail() }
                }
                .disabled(busy || emailPassword.isEmpty || !emailInput.contains("@"))
            } header: {
                Text("找回邮箱")
            } footer: {
                Text("绑定后忘记密码可用邮箱验证码重置。邮箱在服务端是加密存储的，"
                     + "只有你本人能看到明文。")
            }

            Section("修改密码") {
                SecureField("当前密码", text: $oldPassword)
                SecureField("新密码（≥8 位）", text: $newPassword)
                SecureField("再输一次", text: $confirmPassword)
                Button("修改密码") { Task { await changePassword() } }
                    .disabled(busy || oldPassword.isEmpty || newPassword.count < 8
                              || newPassword != confirmPassword)
            }

            if let error = model.error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
        }
        .navigationTitle("账号安全")
        .task { await refreshStatus() }
    }

    private func bindEmail() async {
        busy = true
        defer { busy = false }
        do {
            try await model.bindEmail(password: emailPassword, email: emailInput)
            emailPassword = ""
            emailInput = ""
        } catch {
            model.report(error, prefix: "绑定邮箱失败")
        }
    }

    private func refreshStatus() async {
        await model.loadProfile()
        loading = true
        defer { loading = false }
        twoFactorEnabled = (try? await model.twoFactorStatus()) ?? false
    }

    private func loadSetup() async {
        busy = true
        defer { busy = false }
        do {
            setup = try await model.setupTwoFactor()
        } catch {
            model.report(error, prefix: "生成密钥失败")
        }
    }

    private func enableTwoFactor() async {
        busy = true
        defer { busy = false }
        do {
            let result = try await model.enableTwoFactor(code: setupCode)
            recoveryCodes = result.recoveryCodes ?? []
            setup = nil
            setupCode = ""
            twoFactorEnabled = true
        } catch {
            model.report(error, prefix: "启用失败")
        }
    }

    private func disableTwoFactor() async {
        busy = true
        defer { busy = false }
        do {
            try await model.disableTwoFactor(password: disablePassword, code: disableCode)
            disablePassword = ""
            disableCode = ""
            recoveryCodes = []
            twoFactorEnabled = false
        } catch {
            model.report(error, prefix: "关闭失败")
        }
    }

    private func changePassword() async {
        busy = true
        defer { busy = false }
        do {
            try await model.changePassword(old: oldPassword, new: newPassword)
            oldPassword = ""
            newPassword = ""
            confirmPassword = ""
            model.showNotice("密码已修改，其它设备需要重新登录")
        } catch {
            model.report(error, prefix: "修改密码失败")
        }
    }
}
