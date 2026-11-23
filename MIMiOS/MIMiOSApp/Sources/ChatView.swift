import SwiftUI
import PhotosUI
import MIMKit

/// 聊天页：气泡 + 输入区。
struct ChatView: View {
    @EnvironmentObject private var model: AppModel
    @FocusState private var inputFocused: Bool
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            transcript
        }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    await model.sendImage(data)
                }
                pickerItem = nil
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                model.closeChat()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.body.weight(.semibold))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(model.activePeer ?? "").font(.headline)
                Text("端到端加密")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if model.messages.isEmpty {
                        Text("还没有消息。发出第一条后，对方必须登录过才能收到。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.top, 24)
                    }
                    ForEach(model.messages, id: \.id) { message in
                        MessageBubble(
                            message: message,
                            readByThem: model.isReadByPeer(message)
                        )
                        .id(message.id)
                    }
                }
                .padding(.horizontal, 14)
                // 底部留白：最后一条气泡贴着输入区会显得被切掉。
                .padding(.top, 12)
                .padding(.bottom, 20)
            }
            // 用单参数版本：iOS 16 的最低支持线，`{ _, _ in }` 要 iOS 17。
            // 输入区做成 safeAreaInset 而不是 VStack 的最后一个子项：
            // 否则 ScrollView 的可视区会延伸到输入区下方，最后一条气泡永远被压住。
            .safeAreaInset(edge: .bottom) { composer }
            .onChange(of: model.messages.count) { _ in
                scrollToBottom(proxy)
            }
            .onAppear { scrollToBottom(proxy) }
        }
    }

    /// 滚到底部。用 `Task` 让出一帧：刚插入的气泡还没完成布局时，
    /// 立刻 `scrollTo` 会算在旧高度上，表现为「滚了但没滚到底」。
    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let last = model.messages.last else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 30_000_000)
            withAnimation(.easeOut(duration: 0.18)) {
                proxy.scrollTo(last.id, anchor: .bottom)
            }
        }
    }

    private var composer: some View {
        HStack(spacing: 10) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Image(systemName: "photo.on.rectangle")
                    .font(.title3)
            }
            .disabled(model.busy)
            TextField("输入消息", text: $model.draft, axis: .vertical)
                .lineLimit(1...4)
                .textFieldStyle(.roundedBorder)
                .focused($inputFocused)
                .onSubmit { Task { await model.sendDraft() } }

            Button {
                Task { await model.sendDraft() }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
            }
            .disabled(model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

private struct MessageBubble: View {
    let message: MessageVault.StoredMessage
    /// 对方已读（仅对我方消息有意义）。
    let readByThem: Bool

    var body: some View {
        HStack {
            if message.fromMe { Spacer(minLength: 40) }
            VStack(alignment: message.fromMe ? .trailing : .leading, spacing: 4) {
                if message.kind == "image", let mediaId = message.mediaId {
                    ImageBubble(mediaId: mediaId, message: message)
                }
                // 图片没有配文时不再画一行「[图片]」占位：气泡本身就是图片。
                if !(message.kind == "image" && message.text.isEmpty) {
                    Text(messageDisplayText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        message.fromMe
                            ? AnyShapeStyle(Color.accentColor.opacity(0.16))
                            : AnyShapeStyle(Color(uiColor: .secondarySystemBackground)),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                    )
                    .foregroundStyle(message.recalled ? .secondary : .primary)
                }

                HStack(spacing: 6) {
                    if message.fromMe && readByThem {
                        Text("已读").foregroundStyle(.secondary)
                    }
                    Text(Self.timeText(message.epochMs))
                    if let from = message.syncedFrom, !from.isEmpty {
                        Text("来自另一台设备")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            if !message.fromMe { Spacer(minLength: 40) }
        }
    }

    /// 媒体消息的正文为空，用类型占位；文本消息原样显示。
    private var messageDisplayText: String {
        if message.recalled { return "已撤回" }
        if message.kind == "image" { return message.text.isEmpty ? "[图片]" : message.text }
        return message.text
    }

    private static func timeText(_ epochMs: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
    }
}


/// 图片气泡：先显示占位，解密后替换成图片。
private struct ImageBubble: View {
    @EnvironmentObject private var model: AppModel
    let mediaId: String
    let message: MessageVault.StoredMessage

    var body: some View {
        Group {
            if let image = model.loadedImages[mediaId] {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 220, maxHeight: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(uiColor: .tertiarySystemBackground))
                    .frame(width: 160, height: 120)
                    .overlay(ProgressView())
                    .task { await model.loadImage(for: message) }
            }
        }
    }
}
