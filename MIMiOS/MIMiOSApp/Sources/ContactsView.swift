import SwiftUI
import MIMKit

/// 通讯录：好友列表 + 待处理的好友申请。
///
/// 与桌面端一致：**只有好友之间才能对话**——这里是发起会话的唯一入口。
struct ContactsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showAddFriend = false
    @State private var pendingRemove: String?

    var body: some View {
        List {
            if !model.inboundRequests.isEmpty {
                Section("好友申请（\(model.inboundRequests.count)）") {
                    ForEach(model.inboundRequests) { request in
                        HStack(spacing: 12) {
                            AvatarView(name: request.username,
                                       image: model.avatars[request.username], size: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(request.username).font(.subheadline.weight(.medium))
                                Text("请求添加你为好友")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("同意") { Task { await model.acceptRequest(request) } }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            Button("拒绝") { Task { await model.rejectRequest(request) } }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            if !outboundPending.isEmpty {
                Section("已发出") {
                    ForEach(outboundPending) { request in
                        HStack {
                            Text(request.username)
                            Spacer()
                            Text("等待对方同意")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("好友（\(model.contacts.count)）") {
                if model.contacts.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("还没有好友").font(.headline)
                        Text("点右上角「＋」搜索用户名，对方同意后即可开始加密对话。")
                            .font(.footnote).foregroundStyle(.secondary)
                        Button {
                            showAddFriend = true
                        } label: {
                            Label("添加好友", systemImage: "person.badge.plus")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 6)
                }
                ForEach(model.contacts) { user in
                    Button {
                        model.openChat(user.username)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: user.username,
                                       image: model.avatars[user.username], size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(user.displayName).font(.body)
                                Text(user.username)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Circle()
                                .fill(user.online == true ? Color.green : Color.secondary.opacity(0.35))
                                .frame(width: 8, height: 8)
                        }
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing) {
                        Button("删除", role: .destructive) {
                            pendingRemove = user.username
                        }
                    }
                }
            }

            if let error = model.error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
            // 底部留白：悬浮标签栏会盖住最后一行。
            Section { Color.clear.frame(height: 24) }
        }
        .navigationTitle("通讯录")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAddFriend = true
                } label: {
                    Image(systemName: "person.badge.plus")
                }
                .accessibilityLabel("添加好友")
            }
        }
        .refreshable { await model.loadContacts() }
        .sheet(isPresented: $showAddFriend) {
            NavigationStack { AddFriendView() }
        }
        .confirmationDialog("删除后双方都不再是好友", isPresented: Binding(
            get: { pendingRemove != nil },
            set: { if !$0 { pendingRemove = nil } }
        ), titleVisibility: .visible) {
            Button("删除好友", role: .destructive) {
                if let peer = pendingRemove { Task { await model.removeFriend(peer) } }
                pendingRemove = nil
            }
            Button("取消", role: .cancel) { pendingRemove = nil }
        }
    }

    private var outboundPending: [MIMKit.FriendRequest] {
        model.friendRequests.filter { !$0.isInbound && $0.isPending }
    }
}

/// 添加好友：输入用户名搜索 → 发申请。
struct AddFriendView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var keyword = ""

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("对方的用户名", text: $keyword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { Task { await model.search(keyword) } }
                    Button {
                        Task { await model.search(keyword) }
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .disabled(keyword.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }

            if model.searching {
                Section { ProgressView() }
            }

            if !model.searchResults.isEmpty {
                Section("搜索结果") {
                    ForEach(model.searchResults) { user in
                        HStack(spacing: 12) {
                            AvatarView(name: user.username,
                                       image: model.avatars[user.username], size: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(user.nickname ?? user.username).font(.body)
                                Text(user.username).font(.caption).foregroundStyle(.secondary)
                                if user.canReceive == false {
                                    Text("对方还没有加密身份，收不到消息")
                                        .font(.caption2).foregroundStyle(.orange)
                                }
                            }
                            Spacer()
                            if model.isFriend(user.username) {
                                Text("已是好友").font(.caption).foregroundStyle(.secondary)
                            } else {
                                Button("添加") {
                                    Task { await model.sendFriendRequest(to: user.username) }
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                    }
                }
            } else if !keyword.isEmpty && !model.searching {
                Section {
                    Text("没有找到这个用户（或对方已是你的好友）")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }

            if let error = model.error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
        }
        .navigationTitle("添加好友")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("关闭") { dismiss() }
            }
        }
    }
}
