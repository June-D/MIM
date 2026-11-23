import SwiftUI

/// 登录页。M1 的最小闭环：地址 + 账号 + 密码（+ 需要时的二次验证码）。
struct LoginView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Form {
            Section("服务端") {
                TextField("https://mim.example.com", text: $model.serverURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Text("模拟器可用 http://127.0.0.1:8080；真机必须用 https，客户端会拒绝非回环的明文地址。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("账号") {
                TextField("用户名", text: $model.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("密码", text: $model.password)
                if model.needsTwoFactor {
                    TextField("二次验证码 / 恢复码", text: $model.twoFactorCode)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }
            }

            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }

            Section {
                Button {
                    Task { await model.login() }
                } label: {
                    HStack {
                        Spacer()
                        if model.busy {
                            ProgressView()
                        } else {
                            Text(model.needsTwoFactor ? "验证并登录" : "登录")
                        }
                        Spacer()
                    }
                }
                .disabled(model.busy || model.username.isEmpty || model.password.isEmpty)
            }
        }
        .navigationTitle("MiM")
        .onAppear {
            #if DEBUG
            model.applyDemoPrefill()
            #endif
        }
        .task {
            #if DEBUG
            if model.shouldAutoLogin {
                model.shouldAutoLogin = false
                // 必须用「非结构化 Task」：登录成功会立刻把界面换成设备页，
                // LoginView 消失时 SwiftUI 会取消 `.task` 里正在进行的工作，
                // 表现为发布密钥请求被取消（transport("cancelled")）。
                Task { await model.login() }
            }
            #endif
        }
    }
}
