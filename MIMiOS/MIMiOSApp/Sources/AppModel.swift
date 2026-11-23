import Foundation
import SwiftUI
import CryptoKit
import UIKit
import AVFoundation
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
    /// 已解密并解码好的图片（key 是媒体 id）。只在内存里，不落盘明文。
    @Published private(set) var loadedImages: [String: UIImage] = [:]
    /// 正在播放的语音媒体 id（用于按钮状态）。
    @Published private(set) var playingVoiceId: String?
    /// 正在录音。
    @Published private(set) var recording = false
    /// 最近一条推送的类型，排查用（DEBUG 下显示在聊天页副标题）。
    @Published private(set) var lastPush = "-"

    private let keychain = Keychain()
    private var client: MIMClient?
    private var session: MessagingSession?
    private var vault: MessageVault?
    private var snapshot = MessageVault.Snapshot()
    private var pollTask: Task<Void, Never>?
    private var player: AVAudioPlayer?
    private let playerDelegate = PlayerDelegate()
    private let recorder = AudioRecorder()
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
            migrateLegacyMediaMessages()
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
            // 联调：应用内生成一张图片发出去，验证「App 加密 → 上传 → 发消息」整条路径。
            if ProcessInfo.processInfo.environment["MIM_DEMO_SEND_IMAGE"] == "1",
               activePeer != nil {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                await sendImage(Self.demoImage(), name: "demo.png")
            }
            // 联调：应用内发一个文件，验证 sendFile 路径（文档选择器无法自动化）。
            if ProcessInfo.processInfo.environment["MIM_DEMO_SEND_FILE"] == "1",
               activePeer != nil {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                let bytes = Data((0..<24_000).map { UInt8($0 % 251) })
                await sendFile(data: bytes, name: "季度预算.xlsx")
            }
            // 联调：自动播放最后一条语音，验证解密与 AVAudioPlayer 初始化。
            if ProcessInfo.processInfo.environment["MIM_DEMO_PLAY_VOICE"] == "1" {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if let voice = snapshot.messages.last(where: { $0.kind == "voice" }) {
                    await toggleVoice(voice)
                }
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
            // 联调：发完立刻撤回，验证「撤回」这条链路（弹窗无法自动化）。
            if ProcessInfo.processInfo.environment["MIM_DEMO_RECALL"] == "1" {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if let last = snapshot.messages.last(where: { $0.fromMe && !$0.recalled }) {
                    await recall(last)
                }
            }
            // 联调：发完立刻清除会话。
            if ProcessInfo.processInfo.environment["MIM_DEMO_CLEAR"] == "1" {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await clearCurrentConversation()
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
            let sent = try await session.sendText(to: peer, text: text)
            append(MessageVault.StoredMessage(
                // 用服务端返回的 clientMsgId：撤回、已读回执都按它对齐。
                id: sent.clientMsgId,
                peer: peer, text: text,
                epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                fromMe: true,
                recallToken: sent.recallToken
            ))
            persist()
        } catch {
            self.error = "\(error)"
            draft = text
        }
        _ = client
        _ = token
    }

    /// 发送图片：加密 → 上传 → 把载荷当作消息正文发出去。
    ///
    /// 媒体本身走 `POST /media`（服务端只见密文），消息里只带 id + 一次性密钥。
    func sendImage(_ data: Data, name: String? = nil) async {
        await sendMedia(kind: "image", data: data, mime: "image/jpeg",
                        name: name, durationMs: nil)
    }

    /// 发送任意文件（文档选择器选出来的东西）。
    func sendFile(data: Data, name: String) async {
        await sendMedia(kind: "file", data: data, mime: mimeOf(name),
                        name: name, durationMs: nil)
    }

    /// 发送语音（已录好的 m4a 字节）。
    func sendVoice(data: Data, durationMs: Int64) async {
        await sendMedia(kind: "voice", data: data, mime: "audio/mp4",
                        name: "voice.m4a", durationMs: durationMs)
    }

    /// 图片 / 文件 / 语音共用的发送路径：加密 → 上传 → 消息带引用。
    func sendMedia(kind: String, data: Data, mime: String, name: String?,
                   durationMs: Int64?) async {
        guard let peer = activePeer, let session, let vault else { return }
        busy = true
        defer { busy = false }
        do {
            let encrypted = try MediaCrypto.encrypt(data)
            let mediaId = try await uploadBlob(encrypted.blob)
            let payload = MediaPayload(
                kind: kind,
                text: "",
                media: MediaRef(
                    id: mediaId,
                    key: encrypted.keyBase64,
                    size: data.count,
                    mime: mime,
                    name: name,
                    width: nil,
                    height: nil,
                    durationMs: durationMs
                )
            )
            let json = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
            let sent = try await session.sendText(to: peer, text: json)
            vault.saveMedia(mediaId, blob: encrypted.blob)
            if kind == "image", let image = UIImage(data: data) {
                loadedImages[mediaId] = image
            }
            append(MessageVault.StoredMessage(
                id: sent.clientMsgId,
                peer: peer,
                text: payload.text,
                epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                fromMe: true,
                kind: kind,
                mediaId: mediaId,
                mediaMime: mime,
                mediaName: name,
                mediaSize: data.count,
                mediaKey: encrypted.keyBase64,
                mediaDurationMs: durationMs
            ))
            persist()
        } catch {
            self.error = "发送附件失败：\(error)"
        }
    }

    // MARK: - 语音

    /// 开始录音（首次会申请麦克风权限）。
    func startRecording() async {
        guard !recording else { return }
        let granted = await AudioRecorder.requestPermission()
        guard granted else {
            error = "没有麦克风权限。到「设置 → MiM → 麦克风」里打开后重试。"
            return
        }
        do {
            try recorder.start()
            recording = true
        } catch {
            self.error = "无法开始录音：\(error)"
        }
    }

    /// 停止并发送。
    func stopRecordingAndSend() async {
        guard recording else { return }
        recording = false
        guard let result = recorder.stop() else { return }
        await sendVoice(data: result.data, durationMs: result.durationMs)
    }

    func cancelRecording() {
        recording = false
        recorder.cancel()
    }

    /// 播放/停止某条语音。
    ///
    /// 解密后的字节写到临时文件再交给 `AVAudioPlayer`：它不从 Data 播放，
    /// 而我们又不想把明文长期落在沙盒里，所以用完即删。
    func toggleVoice(_ message: MessageVault.StoredMessage) async {
        guard let id = message.mediaId, let key = message.mediaKey else { return }
        if playingVoiceId == id {
            player?.stop()
            player = nil
            playingVoiceId = nil
            return
        }
        guard let vault else { return }
        var blob = vault.loadMedia(id)
        if blob == nil, let client {
            blob = try? await client.downloadMedia(id)
            if let blob { vault.saveMedia(id, blob: blob) }
        }
        guard let blob,
              let plain = try? MediaCrypto.decrypt(blob: blob, keyBase64: key) else {
            error = "语音已过期或被清理"
            return
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("play-\(id).m4a")
        do {
            try plain.write(to: url)
            player = try AVAudioPlayer(contentsOf: url)
            // 播完要把状态复位，否则按钮一直停在「停止」——看起来像卡住了。
            playerDelegate.onFinish = { [weak self] in
                Task { @MainActor in
                    self?.playingVoiceId = nil
                    self?.player = nil
                }
            }
            player?.delegate = playerDelegate
            player?.prepareToPlay()
            player?.play()
            playingVoiceId = id
        } catch {
            self.error = "无法播放：\(error)"
        }
    }

    // MARK: - 撤回与清除

    /// 撤回自己发出的一条消息。
    ///
    /// 服务端按 clientMsgId 撤掉所有设备副本，并给对端推 100014；
    /// 本机立刻把气泡改成「已撤回」，不等推送回来。
    func recall(_ message: MessageVault.StoredMessage) async {
        guard message.fromMe, !message.recalled else { return }
        guard let token = message.recallToken, let client else {
            // 旧版本发出的消息没存撤回凭证：明确告诉用户，而不是点了没反应。
            error = "这条消息没有撤回凭证（升级前发出的），无法撤回"
            return
        }
        do {
            try await client.recallMessage(
                clientMsgId: message.id, recallToken: token,
                tags: session?.tags(for: message.peer) ?? []
            )
            if let index = snapshot.messages.firstIndex(where: { $0.id == message.id }) {
                snapshot.messages[index].recalled = true
                snapshot.messages[index].text = "已撤回"
            }
            reloadMessages()
            refreshConversations()
            persist()
        } catch {
            self.error = "撤回失败：\(error)"
        }
    }

    /// 清除与当前联系人的会话（单方）。
    ///
    /// 只做单方：双向清除需要按会话标签抹掉服务端密文，而 iOS 没有标签。
    /// 服务端仍会记一个「清除点」，对端之后发来的消息不受影响。
    func clearCurrentConversation() async {
        guard let peer = activePeer, let client else { return }
        do {
            try await client.clearConversation(
                peer: peer, tags: session?.tags(for: peer) ?? []
            )
            snapshot.messages.removeAll { $0.peer == peer }
            var readUpTo = snapshot.readUpTo ?? [:]
            readUpTo.removeValue(forKey: peer)
            snapshot.readUpTo = readUpTo
            messages = []
            refreshConversations()
            persist()
            closeChat()
            notice = "已清除与 \(peer) 的会话"
        } catch {
            self.error = "清除会话失败：\(error)"
        }
    }

    /// 解出某条消息的图片（先查缓存，再查本地密文，最后从服务端下载）。
    func loadImage(for message: MessageVault.StoredMessage) async {
        guard let id = message.mediaId, let key = message.mediaKey else { return }
        if loadedImages[id] != nil { return }
        guard let vault, let client else { return }
        var blob = vault.loadMedia(id)
        if blob == nil {
            blob = try? await client.downloadMedia(id)
            if let blob { vault.saveMedia(id, blob: blob) }
        }
        guard let blob,
              let plain = try? MediaCrypto.decrypt(blob: blob, keyBase64: key),
              let image = UIImage(data: plain) else {
            return
        }
        loadedImages[id] = image
    }

    private func uploadBlob(_ blob: Data) async throws -> String {
        guard let client else { throw MIMApiError.transport("未登录") }
        return try await client.uploadMedia(blob)
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
                // 媒体消息的正文是一段 JSON 载荷：逐字对应桌面端的 MediaPayload。
                if let payload = Self.parseMediaPayload(item.text) {
                    append(MessageVault.StoredMessage(
                        id: item.clientMsgId.isEmpty ? UUID().uuidString.lowercased() : item.clientMsgId,
                        peer: peer, text: payload.text,
                        epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                        fromMe: false,
                        kind: payload.kind,
                        mediaId: payload.media.id,
                        mediaMime: payload.media.mime,
                        mediaName: payload.media.name,
                        mediaSize: payload.media.size,
                        mediaKey: payload.media.key,
                        mediaDurationMs: payload.media.durationMs
                    ))
                    continue
                }
                if item.recalled,
                   let index = snapshot.messages.firstIndex(where: { $0.id == item.clientMsgId }) {
                    // 撤回是对「已存在的那条」的修改，不能当成新消息追加。
                    snapshot.messages[index].recalled = true
                    snapshot.messages[index].text = "已撤回"
                    reloadMessages()
                    refreshConversations()
                    persist()
                    continue
                }
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

    /// 识别媒体载荷。
    ///
    /// 不能按 `{"v":1,"kind":` 前缀判断：JSON 键顺序不保证（Swift 的 JSONEncoder
    /// 就可能把 text 排到最前），桌面端的 Kotlin 序列化器才有固定顺序。
    /// 这里只看「像不像一段短 JSON」，再靠解码结果说话。
    static func parseMediaPayload(_ text: String) -> MediaPayload? {
        guard text.hasPrefix("{"), text.count < 8_000 else { return nil }
        guard let payload = try? JSONDecoder().decode(MediaPayload.self, from: Data(text.utf8))
        else { return nil }
        // 只认已知类型：普通文本恰好是 JSON 时不该被当作附件。
        guard ["image", "file", "voice"].contains(payload.kind) else { return nil }
        return payload
    }

    /// 生成一张带渐变的测试图片（仅 DEBUG 联调用）。
    static func demoImage() -> Data {
        let size = CGSize(width: 240, height: 160)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.pngData { context in
            let colors = [UIColor.systemTeal.cgColor, UIColor.systemPink.cgColor]
            guard let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                            colors: colors as CFArray, locations: [0, 1]) else {
                return
            }
            context.cgContext.drawLinearGradient(
                gradient, start: .zero,
                end: CGPoint(x: size.width, y: size.height), options: []
            )
        }
    }

    /// 老数据兼容：早期版本把媒体载荷当普通文本存了下来（界面上显示成一坨 JSON）。
    ///
    /// 这里就地升级成附件消息——媒体 id 与密钥都在载荷里，不用重新下载。
    private func migrateLegacyMediaMessages() {
        var changed = false
        for index in snapshot.messages.indices {
            let message = snapshot.messages[index]
            guard message.kind == "text",
                  let payload = Self.parseMediaPayload(message.text) else { continue }
            snapshot.messages[index].kind = payload.kind
            snapshot.messages[index].text = payload.text
            snapshot.messages[index].mediaId = payload.media.id
            snapshot.messages[index].mediaKey = payload.media.key
            snapshot.messages[index].mediaMime = payload.media.mime
            snapshot.messages[index].mediaName = payload.media.name
            snapshot.messages[index].mediaSize = payload.media.size
            snapshot.messages[index].mediaDurationMs = payload.media.durationMs
            changed = true
        }
        if changed {
            refreshConversations()
            persist()
        }
    }

    /// 按扩展名推断 MIME；认不出来就交给服务端当二进制处理。
    func mimeOf(_ name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "pdf": return "application/pdf"
        case "zip": return "application/zip"
        case "txt", "md": return "text/plain"
        case "m4a": return "audio/mp4"
        case "mp3": return "audio/mpeg"
        case "mp4", "mov": return "video/mp4"
        default: return "application/octet-stream"
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

/// `AVAudioPlayerDelegate` 要求 NSObject，而 AppModel 是 ObservableObject，
/// 所以用一个小对象转发回调，避免把 AppModel 变成 NSObject 子类。
final class PlayerDelegate: NSObject, AVAudioPlayerDelegate {
    var onFinish: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
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
