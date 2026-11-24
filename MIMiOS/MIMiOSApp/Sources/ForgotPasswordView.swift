import SwiftUI

/// 忘记密码：用户名 → 邮箱验证码 → 新密码。
///
/// 验证码发到账号**已绑定**的邮箱；没绑定时这条通道不可用，界面直说，
/// 而不是让用户反复点「发送」猜哪里错了。
struct ForgotPasswordView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var username = ""
    @State private var code = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var sent = false
    @State private var cooldown = 0
    @State private var busy = false
    @State private var done = false

    private var canSend: Bool {
        !busy && cooldown == 0 && !username.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var canReset: Bool {
        !busy && code.count >= 4 && newPassword.count >= 8 && newPassword == confirmPassword
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("用户名", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button(sent ? "重新发送验证码" : "发送验证码") {
                        Task { await sendCode() }
                    }
                    .disabled(!canSend)
                    if cooldown > 0 {
                        Text("\(cooldown) 秒后可重新发送")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("验证码会发到该账号绑定的找回邮箱，10 分钟内有效。")
                }

                Section("重置密码") {
                    TextField("邮箱验证码", text: $code)
                        .keyboardType(.numberPad)
                    SecureField("新密码（≥8 位）", text: $newPassword)
                    SecureField("再输一次", text: $confirmPassword)
                    Button("重置并返回登录") {
                        Task { await reset() }
                    }
                    .disabled(!canReset)
                }

                if let error = model.error {
                    Section { Text(error).font(.footnote).foregroundStyle(.red) }
                }

                if done {
                    Section {
                        Label("密码已重置，请用新密码登录", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("找回密码")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    private func sendCode() async {
        busy = true
        model.dismissError()
        defer { busy = false }
        do {
            try await model.requestRecoveryCode(
                username: username.trimmingCharacters(in: .whitespaces)
            )
            sent = true
            cooldown = 60
            Task { await tick() }
        } catch {
            model.report(error, prefix: "发送验证码失败")
        }
    }

    private func tick() async {
        while cooldown > 0 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            cooldown -= 1
        }
    }

    private func reset() async {
        busy = true
        model.dismissError()
        defer { busy = false }
        do {
            try await model.resetPassword(
                username: username.trimmingCharacters(in: .whitespaces),
                code: code, newPassword: newPassword
            )
            done = true
            // 服务端已把该账号所有设备下线：这里清掉本地登录态，回登录页。
            model.logout()
            dismiss()
        } catch {
            model.report(error, prefix: "重置失败")
        }
    }
}
