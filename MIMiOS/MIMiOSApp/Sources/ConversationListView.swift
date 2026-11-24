import SwiftUI

/// 会话列表：M2 的入口页。
struct ConversationListView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showNewChat = false
    @State private var showDevices = false
    /// 待确认清除的会话（列表长按 → 清除）。
    @State private var pendingClear: String?

    var body: some View {
        List {
            if !model.pushConnected {
                // 长连接断开时给个明确提示：否则用户会以为「对方没回」。
                HStack(spacing: 6) {
                    Image(systemName: "wifi.exclamationmark")
                    Text("连接已断开，正在重连…")
                    Spacer()
                }
                .font(.footnote)
                .foregroundStyle(.orange)
            }
            if model.conversations.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("还没有会话").font(.headline)
                        Text("点右上角「写消息」输入对方用户名开始聊天。消息端到端加密，服务端只转发密文。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                }
            }

            Section {
                ForEach(model.conversations) { conversation in
                    Button {
                        model.openChat(conversation.id)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(
                                name: conversation.id,
                                image: model.avatars[conversation.id],
                                size: 44
                            )
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(conversation.id).font(.headline)
                                    Spacer()
                                    Text(ChatTime.list(conversation.lastEpochMs))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                HStack(spacing: 6) {
                                    Text(conversation.lastText)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                    Spacer()
                                    if conversation.unread > 0 {
                                        Text("\(conversation.unread)")
                                            .font(.caption2.weight(.semibold))
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 7)
                                            .padding(.vertical, 2)
                                            .background(Color.accentColor, in: Capsule())
                                    }
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            model.openChat(conversation.id)
                        } label: {
                            Label("打开", systemImage: "bubble.left")
                        }
                        Button(role: .destructive) {
                            pendingClear = conversation.id
                        } label: {
                            Label("清除会话", systemImage: "trash")
                        }
                    }
                }
            }

            if let notice = model.notice {
                Section {
                    Text(notice).font(.footnote).foregroundStyle(.secondary)
                }
            }

            Section {
                Button {
                    showNewChat = true
                } label: {
                    Label("写消息", systemImage: "square.and.pencil")
                }
                Button {
                    showDevices = true
                } label: {
                    Label("我的设备", systemImage: "laptopcomputer.and.iphone")
                }
                Button("退出登录", role: .destructive) {
                    model.logout()
                }
            }

            if let error = model.error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
        }
        .navigationTitle("MiM")
        .refreshable { await model.refreshInbox() }
        .confirmationDialog("清除后本机将看不到这段对话", isPresented: Binding(
            get: { pendingClear != nil },
            set: { if !$0 { pendingClear = nil } }
        ), titleVisibility: .visible) {
            Button("清除会话", role: .destructive) {
                if let peer = pendingClear {
                    Task { await model.clearConversation(peer) }
                }
                pendingClear = nil
            }
            Button("取消", role: .cancel) { pendingClear = nil }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showNewChat = true
                } label: {
                    Image(systemName: "square.and.pencil")
                }
            }
        }
        .sheet(isPresented: $showNewChat) {
            NewChatSheet()
        }
        .sheet(isPresented: $showDevices) {
            NavigationStack { DeviceListView().environmentObject(model) }
        }
        .refreshable { await model.receiveOnce() }
    }

}

/// 开始新会话：输入对方用户名。
///
/// 没有通讯录接口之前，先用手输用户名——服务端本来就是按用户名寻址，
/// 少一层「加好友」反而更直接。
private struct NewChatSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var peer = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("对方用户名") {
                    TextField("例如 composebob", text: $peer)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section {
                    Text("对方必须先登录过一次（上传了预密钥），否则无法建立会话。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("写消息")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("开始") {
                        model.openChat(peer)
                        dismiss()
                    }
                    .disabled(peer.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
