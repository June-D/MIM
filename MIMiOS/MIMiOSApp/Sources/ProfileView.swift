import SwiftUI
import MIMKit

/// 「我」：本机身份、设备管理、本地数据与登录态。
///
/// 桌面端把「我的设备」和「安全」分成两个页面，移动端合并成一张设置页，
/// 层次少一层，常用操作一屏能看到。
struct ProfileView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showResetConfirm = false
    @State private var showWipeConfirm = false
    @State private var pendingRevoke: String?

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    AvatarView(name: model.username,
                               image: model.avatars[model.username], size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(model.username.isEmpty ? "未登录" : model.username)
                            .font(.title3.weight(.semibold))
                        Text(model.identityFingerprint.isEmpty
                             ? "身份指纹尚未生成" : model.identityFingerprint)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
            }

            Section("身份") {
                LabeledContent("设备标识") {
                    Text(model.deviceId.prefix(8) + "…")
                        .font(.footnote.monospaced())
                        .foregroundStyle(.secondary)
                }
                Text("和桌面端「我的设备」比对同一账号的指纹，不一致说明本机身份与账号不匹配。")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            Section("设备（\(model.devices.count)）") {
                ForEach(model.devices, id: \.deviceId) { device in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(device.deviceName ?? device.deviceId)
                                if device.deviceId == model.deviceId {
                                    Text("本机")
                                        .font(.caption2)
                                        .padding(.horizontal, 6).padding(.vertical, 1)
                                        .background(.tint.opacity(0.15), in: Capsule())
                                }
                            }
                            Text(device.deviceId.prefix(12) + "…")
                                .font(.caption2.monospaced())
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .swipeActions(edge: .trailing) {
                        if device.deviceId != model.deviceId {
                            Button("注销", role: .destructive) {
                                pendingRevoke = device.deviceId
                            }
                        }
                    }
                }
            }

            Section {
                Button("刷新设备") { Task { await model.loadDevices() } }
                Button("清除本地聊天记录", role: .destructive) { showWipeConfirm = true }
                Button("重置本机身份", role: .destructive) { showResetConfirm = true }
            } footer: {
                Text("重置身份会重新生成密钥并退出登录：对方会看到你成为「新设备」，"
                     + "本地历史也会清空。仅在身份与服务端不一致时使用。")
            }

            Section {
                Button("退出登录", role: .destructive) { model.logout() }
            }

            if let error = model.error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
            // 底部留白：悬浮标签栏会盖住最后一行。
            Section { Color.clear.frame(height: 24) }
        }
        .navigationTitle("我")
        .refreshable { await model.loadDevices() }
        .confirmationDialog(
            "将重新生成身份密钥并清空本地记录，然后退出登录。",
            isPresented: $showResetConfirm, titleVisibility: .visible
        ) {
            Button("重置身份", role: .destructive) { model.resetIdentity() }
            Button("取消", role: .cancel) {}
        }
        .confirmationDialog("只清本机的聊天记录，身份与登录态保留",
                            isPresented: $showWipeConfirm, titleVisibility: .visible) {
            Button("清除记录", role: .destructive) { model.wipeLocalMessages() }
            Button("取消", role: .cancel) {}
        }
        .confirmationDialog("注销后该设备将无法收发消息", isPresented: Binding(
            get: { pendingRevoke != nil },
            set: { if !$0 { pendingRevoke = nil } }
        ), titleVisibility: .visible) {
            Button("注销设备", role: .destructive) {
                if let id = pendingRevoke { Task { await model.revokeDevice(id) } }
                pendingRevoke = nil
            }
            Button("取消", role: .cancel) { pendingRevoke = nil }
        }
    }
}
