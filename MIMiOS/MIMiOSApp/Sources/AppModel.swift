import Foundation
import SwiftUI
import CryptoKit
import UIKit
import MIMKit

/// 一段会话在列表里的摘要。
struct Conversation: Identifiable, Equatable {
    let id: String
    var lastText: String
    var lastEpochMs: Int64
    /// 未读数：按「对方发来、且在本机已读游标之后」计数。
    var unread: Int
}

/// 界面状态与用例编排。
///
/// 协议逻辑全在 MIMKit / Rust 里；这里只负责「调什么、界面显示什么、什么落盘」。
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
    @Published private(set) var deviceId: String = ""
    @Published private(set) var identityFingerprint = ""
    @Published private(set) var devices: [DeviceInfo] = []

    /// 会话列表（按最后一条消息时间倒序）。
    @Published private(set) var conversations: [Conversation] = []
    /// 当前打开的会话与它的消息。
    @Published private(set) var activePeer: String?
    @Published private(set) var messages: [MessageVault.StoredMessage] = []
    @Published var draft = ""
    /// 手动「开始对话」输入的账号。
    @Published var newPeer = ""
    /// 收到新消息时的小提示（列表上可见）。
    @Published private(set) var notice: String?
    /// 对方读到哪儿了（来自 100011 推送）；用于在我发出的气泡下标「已读」。
    @Published private(set) var peerReadAt: [String: Int64] = [:]
    /// 长连接是否在线（界面上不显示，排查用）。
    @Published private(set) var pushConnected = false
    /// 最近一条推送的类型，排查用（DEBUG 下显示在聊天页副标题）。
    @Published private(set) var lastPush = "-"

    private let keychain = Keychain()
    private var client: MIMClient?
    private var session: MessagingSession?
    private var vault: MessageVault?
    private var snapshot = MessageVault.Snapshot()
    private var pollTask: Task<Void, Never>?
    private var pushTask: Task<Void, Never>?

    private static let identityAccount = "identity-bundle"
    private static let deviceIdAccount = "device-id"
    private static let publishedAccount = "keys-published"

    /// 仅 DEBUG：环境变量预填，方便模拟器截图与联调。
    #if DEBUG
    var shouldAutoLogin = false
    var autoOpenPeer: String?
    #endif

    // MARK: - 设备标识

    var deviceID: String { deviceId }

    private func loadDeviceId() -> String {
        if let data = keychain.get(account: Self.deviceIdAccount),
           let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text
        }
        let generated = UUID().uuidString.lowercased()
        try? keychain.set(Data(generated.utf8), account: Self.deviceIdAccount)
        return generated
    }

    // MARK: - 登录

    #if DEBUG
    func applyDemoPrefill() {
        let env = ProcessInfo.processInfo.environment
        if let server = env["MIM_DEMO_SERVER"], !server.isEmpty { serverURL = server }
        if let user = env["MIM_DEMO_USER"], !user.isEmpty { username = user }
        if let pass = env["MIM_DEMO_PASSWORD"], !pass.isEmpty { password = pass }
        if let peer = env["MIM_DEMO_PEER"], !peer.isEmpty { autoOpenPeer = peer }
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

        deviceId = loadDeviceId()
        let client = MIMClient(baseURL: url, deviceId: deviceId)
        do {
            let token = try await client.login(
                username: username, password: password,
                code: needsTwoFactor ? twoFactorCode : nil
            )
            self.client = client
            self.token = token

            let bundle = try ensureIdentityBundle()
            try await publishIfNeeded(client: client, bundle: bundle)

            vault = MessageVault(root: Self.vaultRoot(), account: username)
            snapshot = vault?.load() ?? MessageVault.Snapshot()
            session = MessagingSession(
                client: client, bundle: bundle,
                store: snapshot.store, sessions: snapshot.sessions
            )
            refreshConversations()

            #if DEBUG
            if let peer = autoOpenPeer {
                autoOpenPeer = nil
                openChat(peer)
            }
            // 截图/联调时用环境变量触发一次发送，验证「App → 服务端」这条路径。
            if let text = ProcessInfo.processInfo.environment["MIM_DEMO_SEND_TEXT"],
               !text.isEmpty, activePeer != nil {
                // 等长连接就绪再发：否则对方的「已读回执」可能在 WS 连上之前就被推掉，
                // 截图上就看不到「已读」——这是验证脚本的时序问题，不是功能问题。
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                draft = text
                await sendDraft()
            }
            #endif
            startPush()
            startPolling()
        } catch MIMApiError.twoFactorRequired(let message) {
            needsTwoFactor = true
            error = message
        } catch MIMApiError.invalidCredentials(let message) {
            error = message
        } catch {
            self.error = "\(error)"
        }
    }

    func logout() {
        pollTask?.cancel()
        pollTask = nil
        pushTask?.cancel()
        pushTask = nil
        pushConnected = false
        peerReadAt = [:]
        token = nil
        client = nil
        session = nil
        vault = nil
        snapshot = MessageVault.Snapshot()
        conversations = []
        devices = []
        messages = []
        activePeer = nil
        password = ""
        twoFactorCode = ""
        needsTwoFactor = false
    }

    /// 设备列表（「我的设备」页用）。
    func loadDevices() async {
        guard let client else { return }
        do {
            devices = try await client.devices()
        } catch {
            self.error = "\(error)"
        }
    }

    // MARK: - 会话与消息

    func openChat(_ peer: String) {
        let trimmed = peer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        activePeer = trimmed
        reloadMessages()
        markRead(peer: trimmed)
    }

    func closeChat() {
        activePeer = nil
        messages = []
    }

    /// 把某个会话标记为已读：先更新本地游标（界面立刻变），再上报服务端。
    ///
    /// 服务端要 `clientMsgIds`——各设备副本的会话标签不同，只按 tags 只能清掉
    /// 自己那份，另一台设备的红点不会消失。
    private func markRead(peer: String) {
        let mine = snapshot.messages.filter { $0.peer == peer }.map { $0.id }
        guard !mine.isEmpty else { return }
        let latest = snapshot.messages.filter { $0.peer == peer }.map { $0.epochMs }.max() ?? 0
        if (snapshot.readUpTo?[peer] ?? 0) < latest {
            var readUpTo = snapshot.readUpTo ?? [:]
            readUpTo[peer] = latest
            snapshot.readUpTo = readUpTo
            refreshConversations()
            persist()
        }
        guard let client else { return }
        Task { try? await client.markRead(peer: peer, clientMsgIds: mine) }
    }

    func sendDraft() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let peer = activePeer, let session, let client else { return }
        draft = ""
        guard let token else { return }
        do {
            try await session.sendText(to: peer, text: text)
            append(MessageVault.StoredMessage(
                id: UUID().uuidString.lowercased(),
                peer: peer, text: text,
                epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                fromMe: true
            ))
            persist()
        } catch {
            self.error = "\(error)"
            draft = text
        }
        _ = client
        _ = token
    }

    /// 拉一次未投递消息。`silent` 用于轮询：失败不弹错，避免断网时满屏红字。
    func receiveOnce(silent: Bool = false) async {
        guard let session else { return }
        do {
            let received = try await session.receive()
            guard !received.isEmpty else { return }
            for item in received {
                let peer = item.peer.isEmpty ? (activePeer ?? "") : item.peer
                guard !peer.isEmpty else { continue }
                append(MessageVault.StoredMessage(
                    id: item.clientMsgId.isEmpty ? UUID().uuidString.lowercased() : item.clientMsgId,
                    peer: peer, text: item.text,
                    // 用本机收到的时刻：服务端的时间戳是发送方的时钟，可能偏。
                    epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                    fromMe: false,
                    recalled: item.recalled
                ))
            }
            persist()
            if let peer = activePeer, received.contains(where: { $0.peer == peer || $0.peer.isEmpty }) {
                markRead(peer: peer)
            }
        } catch {
            if !silent { self.error = "\(error)" }
        }
    }

    /// 长连接：收到 100010/100011/100014 就拉一次增量。
    ///
    /// 轮询仍保留（20 秒一次）作为兜底：WS 断线时消息不会无限期延迟，
    /// 重连期间的漏帧也不需要额外的补偿逻辑。
    private func startPush() {
        pushTask?.cancel()
        guard let url = URL(string: serverURL), let token else { return }
        let socket = MIMWebSocket(baseURL: url, token: token, deviceId: deviceId)
        pushTask = Task { [weak self] in
            for await event in socket.events() {
                guard let self else { return }
                await self.handle(event)
            }
        }
    }

    private func handle(_ event: PushEvent) async {
        pushConnected = true
        lastPush = "\(event)"
        switch event {
        case .newData:
            await receiveOnce(silent: true)
        case .readReceipt(let peer):
            // 对方读了我的消息。这里只记「读到当前时刻」——本地没有服务端 id 与
            // 消息的严格映射，逐条对 beforeId 反而会算错。
            if !peer.isEmpty {
                peerReadAt[peer] = Int64(Date().timeIntervalSince1970 * 1000)
            }
        case .readSync(let peer), .cleared(let peer):
            if !peer.isEmpty,
               let latest = snapshot.messages.filter({ $0.peer == peer }).map({ $0.epochMs }).max() {
                var readUpTo = snapshot.readUpTo ?? [:]
                readUpTo[peer] = latest
                snapshot.readUpTo = readUpTo
                refreshConversations()
                persist()
            }
        case .syncRequested:
            // iOS 侧暂不参与历史同步转发（需要用户确认 + 指纹比对，见设计稿）。
            break
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                // 20 秒一次：长连接正常时这只是兜底；断线时也不至于无限期延迟。
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard let self else { return }
                await self.receiveOnce(silent: true)
            }
        }
    }

    private func append(_ message: MessageVault.StoredMessage) {
        // 按 clientMsgId 幂等：重复投递、重连后的补齐都不该在界面上出现两条。
        if snapshot.messages.contains(where: { $0.id == message.id }) {
            return
        }
        snapshot.messages.append(message)
        if message.peer == activePeer {
            messages = snapshot.messages
                .filter { $0.peer == message.peer }
                .sorted { $0.epochMs < $1.epochMs }
        }
        refreshConversations()
        if !message.fromMe {
            notice = "收到来自 \(message.peer) 的消息"
        }
    }

    private func reloadMessages() {
        guard let peer = activePeer else {
            messages = []
            return
        }
        messages = snapshot.messages
            .filter { $0.peer == peer }
            .sorted { $0.epochMs < $1.epochMs }
    }

    private func refreshConversations() {
        var latest: [String: Conversation] = [:]
        let readUpTo = snapshot.readUpTo ?? [:]
        for message in snapshot.messages {
            let current = latest[message.peer]
            let delta = (!message.fromMe && message.epochMs > (readUpTo[message.peer] ?? 0)) ? 1 : 0
            if current == nil || message.epochMs >= current!.lastEpochMs {
                latest[message.peer] = Conversation(
                    id: message.peer,
                    lastText: message.recalled ? "已撤回" : message.text,
                    lastEpochMs: message.epochMs,
                    unread: (current?.unread ?? 0) + delta
                )
            } else {
                latest[message.peer]?.unread = (current?.unread ?? 0) + delta
            }
        }
        conversations = latest.values.sorted { $0.lastEpochMs > $1.lastEpochMs }
    }

    /// 我发出的这条，对方是否已读。
    ///
    /// 只记「对方读到某时刻」的游标：本地没有服务端消息 id 与消息的严格映射，
    /// 逐条比对 beforeId 会算错；按时间比较在单机会话里是准确的。
    func isReadByPeer(_ message: MessageVault.StoredMessage) -> Bool {
        guard message.fromMe, let readAt = peerReadAt[message.peer] else { return false }
        return readAt >= message.epochMs
    }

    /// 落盘：会话状态、会话索引、消息三样一起加密写。
    private func persist() {
        guard let session, let vault else { return }
        snapshot.store = session.store
        snapshot.sessions = session.sessions
        do {
            try vault.save(snapshot)
        } catch {
            self.error = "本地保存失败：\(error)"
        }
    }

    static func vaultRoot() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("MIMLocal", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    // MARK: - 密钥

    /// 首次登录时把公钥发布到服务端，之后不再重复。
    private func publishIfNeeded(client: MIMClient, bundle: KeyBundle) async throws {
        guard keychain.get(account: Self.publishedAccount) == nil else { return }
        try await client.publishKeys(
            password: password,
            deviceName: UIDevice.current.name,
            bundle: bundle
        )
        try? keychain.set(Data("1".utf8), account: Self.publishedAccount)
    }

    /// 本机身份密钥包：首次登录生成并只写 Keychain。
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

    /// SPKI DER 的 SHA-256 前 128 位，4 位分组——与桌面端同一算法。
    private static func fingerprint(ofHex hex: String) -> String {
        let digest = SHA256.hash(data: Data(hexString: hex))
        return digest.prefix(8).map { String(format: "%02X", $0) }
            .joined()
            .chunked(every: 4)
            .joined(separator: " ")
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
