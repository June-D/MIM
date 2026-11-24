import Foundation
import SwiftUI
import CryptoKit
import UIKit
import UserNotifications
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

    /// 默认指向本机局域网地址（真机用这个）。
    ///
    /// 真机不能连 `127.0.0.1`，也**不能**用明文 http——客户端只放行回环地址的明文。
    /// 所以服务端要开 TLS，并在手机上信任这张自签证书（见 MIMiOS/README）。
    @Published var serverURL = "https://172.20.10.2:8443"
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
    /// 已绑定的找回邮箱（未绑定为 nil）。
    @Published private(set) var boundEmail: String?
    /// 好友（通讯录）。会话能不能发消息由它决定：桌面端同样只允许和好友对话。
    @Published private(set) var contacts: [ChatUser] = []
    /// 好友申请（我收到与我发出的）。
    @Published private(set) var friendRequests: [MIMKit.FriendRequest] = []
    /// 搜索到的可添加用户。
    @Published private(set) var searchResults: [UserSummary] = []
    @Published private(set) var searching = false
    /// 当前标签页：0 会话 / 1 通讯录 / 2 我。
    @Published var selectedTab = 0

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
    /// 头像（key 是用户名）。头像是公开资料，服务端可读，不做 E2EE。
    @Published private(set) var avatars: [String: UIImage] = [:]
    /// 正在播放的语音媒体 id（用于按钮状态）。
    @Published private(set) var playingVoiceId: String?
    /// 正在录音。
    @Published private(set) var recording = false
    /// 最近一条推送的类型，排查用（DEBUG 下显示在聊天页副标题）。
    @Published private(set) var lastPush = "-"

    /// 按账号加后缀的 Keychain 键（设备标识 / 身份 / 已发布标记都要分账号）。
    private static func account(_ base: String, _ username: String) -> String {
        "\(base).\(username.lowercased())"
    }

    private let keychain = Keychain()
    private var client: MIMClient?
    private var session: MessagingSession?
    /// 本机身份密钥包（导出历史时要用身份私钥给副本签名）。
    private var bundle: KeyBundle?
    private var vault: MessageVault?
    private var snapshot = MessageVault.Snapshot()
    private var pollTask: Task<Void, Never>?
    private var player: AVAudioPlayer?
    private let playerDelegate = PlayerDelegate()
    private let recorder = AudioRecorder()
    private var pushTask: Task<Void, Never>?

    /// Keychain 里的键必须**按账号**区分。
    ///
    /// 之前是全局单键：在一台手机上先登录 A 再登录 B 时，B 会复用到 A 的
    /// 「已发布公钥」标记，于是跳过 POST /device，服务端就回
    /// 「设备未注册或已注销」——换账号必踩。
    private static let identityAccount = "identity-bundle"
    /// 登录态（令牌 + 账号 + 服务端地址）存 Keychain：
    /// 重开 App 不用重新登录；卸载重装后 Keychain 仍会保留，这也是我们想要的
    /// （身份私钥本来就在 Keychain 里，两者生命周期一致）。
    private static let sessionToken = "session-token"
    private static let sessionUser = "session-user"
    private static let sessionServer = "session-server"
    private static let deviceIdAccount = "device-id"
    private static let publishedAccount = "keys-published"

    /// 仅 DEBUG：环境变量预填，方便模拟器截图与联调。
    #if DEBUG
    var shouldAutoLogin = false
    var autoOpenPeer: String?
    #endif

    /// 启动时尝试恢复上次的登录态。
    ///
    /// 只信任 Keychain 里的令牌不够——它可能已过期或被服务端吊销，
    /// 所以先拿一个轻量接口探一次（`GET /device`），通过了才真正进入登录态。
    func restoreSession() async {
        guard let tokenData = keychain.get(account: Self.sessionToken),
              let token = String(data: tokenData, encoding: .utf8), !token.isEmpty,
              let userData = keychain.get(account: Self.sessionUser),
              let user = String(data: userData, encoding: .utf8), !user.isEmpty,
              let serverData = keychain.get(account: Self.sessionServer),
              let server = String(data: serverData, encoding: .utf8),
              let url = URL(string: server) else {
            return
        }
        // 旧版 Keychain 是全局单键，只能安全地归给「上次登录的那个账号」——
        // 换账号登录时若还去认领，会把别人的身份密钥当成自己的。
        migrateLegacyKeychainIfNeeded(username: user)
        // 设备标识按账号取值，必须先设好 username 再取，否则会拿到空账号的键。
        self.username = user
        let client = MIMClient(
            baseURL: url, deviceId: loadDeviceId(),
            token: token, pinnedCertificate: Self.pinnedCertificate()
        )
        busy = true
        defer { busy = false }
        do {
            let devices = try await client.devices()
            self.serverURL = server
            self.client = client
            self.token = token
            self.deviceId = client.deviceId
            self.devices = devices
            try await startSession(client: client)
        } catch {
            // 令牌失效（服务端重启、过期、被吊销）就静默回到登录页，不打扰用户。
            clearSavedSession()
        }
    }

    /// 把旧版「全局单键」的数据迁到当前账号名下（只做一次）。
    ///
    /// 不迁的话，老用户升级后会重新生成身份密钥（等于换了台设备），
    /// 而另一账号登录时又会误用这个「全局」身份——两种情况都不对。
    private func migrateLegacyKeychainIfNeeded(username: String) {
        for base in [Self.identityAccount, Self.deviceIdAccount, Self.publishedAccount] {
            let legacy = base
            let scoped = Self.account(base, username)
            guard keychain.get(account: scoped) == nil,
                  let value = keychain.get(account: legacy) else { continue }
            try? keychain.set(value, account: scoped)
            keychain.delete(account: legacy)
        }
    }

    private func saveSession(token: String) {
        try? keychain.set(Data(token.utf8), account: Self.sessionToken)
        try? keychain.set(Data(username.utf8), account: Self.sessionUser)
        try? keychain.set(Data(serverURL.utf8), account: Self.sessionServer)
    }

    private func clearSavedSession() {
        keychain.delete(account: Self.sessionToken)
        keychain.delete(account: Self.sessionUser)
        keychain.delete(account: Self.sessionServer)
    }

    // MARK: - 设备标识

    var deviceID: String { deviceId }

    private func loadDeviceId() -> String {
        let scoped = Self.account(Self.deviceIdAccount, username)
        if let data = keychain.get(account: scoped),
           let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text
        }
        let generated = UUID().uuidString.lowercased()
        try? keychain.set(Data(generated.utf8), account: scoped)
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
        let client = MIMClient(
            baseURL: url, deviceId: deviceId,
            pinnedCertificate: Self.pinnedCertificate()
        )
        do {
            let token = try await client.login(
                username: username, password: password,
                code: needsTwoFactor ? twoFactorCode : nil
            )
            self.client = client
            self.token = token
            try await startSession(client: client, password: password)
            saveSession(token: token)
        } catch MIMApiError.twoFactorRequired(let message) {
            needsTwoFactor = true
            error = message
        } catch MIMApiError.invalidCredentials(let message) {
            error = message
        } catch {
            // `cancelled` 基本只有一种来源：TLS 握手被证书锁定拒绝了。
            // 把真实原因翻出来，否则用户只看到一句「登录失败」。
            if case MIMApiError.transport(let message) = error,
               message.lowercased().contains("cancel") {
                self.error = client.lastTrustFailure
                    ?? "连接被中断：可能是网络不通，或服务器证书未被信任"
            } else {
                self.error = "\(error)"
            }
        }
    }

    /// 所有会话的未读总数（用于应用图标角标与列表标题）。
    var totalUnread: Int {
        conversations.reduce(0) { $0 + $1.unread }
    }

    /// 把极光的 registrationID 交给服务端（每台设备一份）。
    ///
    /// registrationID 可能在登录之后才到达，所以既要「现在取一次」，
    /// 也要注册回调「到了再报一次」——两步都做才不会漏。
    private func registerForPush(client: MIMClient) {
        AppDelegate.onRegistration = { [weak self] registrationId in
            Task { try? await self?.client?.reportPushRegistration(registrationId) }
        }
        // 模拟器上 registrationID 为空（resCode 1011），真机才会拿到。
        // 点通知 → 打开对应会话
        AppDelegate.onNotificationTap = { [weak self] info in
            Task { @MainActor in await self?.openConversation(fromPush: info) }
        }
        // 冷启动：通知先到、回调后挂，补消费一次
        if let pending = AppDelegate.pendingTap {
            AppDelegate.pendingTap = nil
            Task { @MainActor in await openConversation(fromPush: pending) }
        }

        let existing = JPUSHService.registrationID()
        guard !existing.isEmpty else { return }
        Task { try? await client.reportPushRegistration(existing) }
    }

    /// 根据推送里的 `conversation_tag` 反查会话对象并打开。
    ///
    /// 为什么服务端不直接给用户名：它没有 from 列（发送者写在密文里），
    /// 也不知道「这个标签对应谁」——只有本机的会话索引知道。
    /// 标签是双方共享的随机串，不含任何身份信息。
    func openConversation(fromPush info: [AnyHashable: Any]) async {
        guard token != nil else { return }
        // 先拉一次增量，保证打开时能看到那条新消息。
        await receiveOnce(silent: true)

        // 极光把自定义 extras 平铺进 userInfo；也兼容包一层的情况。
        let extras = (info["extras"] as? [AnyHashable: Any]) ?? info
        guard let tag = extras["tag"] as? String, !tag.isEmpty else { return }
        let peer = session?.sessions.first { $0.value.tag == tag }?
            .key.split(separator: "|").first.map(String.init)
        #if DEBUG
        let all = session?.sessions ?? [:]
        let empty = all.values.filter { $0.tag.isEmpty }.count
        notice = peer == nil
            ? "未命中：索引 \(all.count) 条，空标签 \(empty) 条"
            : "推送跳转：\(peer!)"
        #endif
        guard let peer, !peer.isEmpty else { return }
        openChat(peer)
    }

    #if DEBUG
    /// 联调：强制退出登录态（验证「换账号」这条路径时用，界面上点退出太慢）。
    func debugLogout() {
        clearSavedSession()
        logout()
    }
    #endif

    /// 令牌失效（服务端 40101）：清掉登录态、回到登录页。
    ///
    /// 不做这件事的后果是**界面看起来正常但什么都发不出去**——请求全被 40101 拒，
    /// 轮询每 20 秒失败一次，用户只会看到「点了没反应」。
    private func expireSessionIfNeeded(_ error: Error) -> Bool {
        guard case MIMApiError.failed(let status, _) = error, status == 40101 else {
            return false
        }
        clearSavedSession()
        logout()
        self.error = "登录已过期，请重新登录"
        return true
    }

    /// 清掉错误提示（界面上有可点的关闭按钮）。
    func dismissError() {
        error = nil
    }

    #if DEBUG
    /// 联调钩子：环境变量驱动的自动化动作（截图、冒烟用）。
    ///
    /// 全部包在 `#if DEBUG` 里——发布版不存在这些路径，也就没有「凭据能从环境变量注入」
    /// 这种奇怪的攻击面。
    private func runDemoHooks() async {
        // 联调：模拟「点了推送通知」，验证 tag → 会话的反查与跳转。
        if let tag = ProcessInfo.processInfo.environment["MIM_DEMO_PUSH_TAG"], !tag.isEmpty {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            await openConversation(fromPush: ["tag": tag])
        }
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
    }
    #endif

    /// 登录成功后的统一初始化（登录与恢复登录态共用）。
    private func startSession(client: MIMClient, password: String? = nil) async throws {
        let bundle = try ensureIdentityBundle()
        self.bundle = bundle
        // 登记设备与发布公钥是两件事：换账号时设备 id 变了，必须重新登记，
        // 否则服务端回「设备未注册或已注销」。
        try await client.registerDevice(deviceName: UIDevice.current.name)
        try await publishIfNeeded(client: client, bundle: bundle)
        if let password, !password.isEmpty {
            try? await reconcilePublishedIdentity(client: client, bundle: bundle, password: password)
        }

        vault = MessageVault(root: Self.vaultRoot(), account: username)
        snapshot = vault?.load() ?? MessageVault.Snapshot()
        migrateLegacyMediaMessages()
        session = MessagingSession(
            client: client, bundle: bundle,
            store: snapshot.store, sessions: snapshot.sessions
        )
        refreshConversations()
        await loadAvatar(username)
        await loadProfile()
        await loadContacts()
        // 新设备本地没有历史：请其它设备转发最近的消息。
        // 只在「完全空库」时请求一次，避免每次启动都打扰另一台设备。
        if snapshot.messages.isEmpty {
            try? await client.requestHistorySync()
            notice = "已请求其它设备同步历史，收到后会直接出现在列表里"
        }
        registerForPush(client: client)
        #if DEBUG
        await runDemoHooks()
        #endif
        startPush()
        startPolling()
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
    /// 手动刷新（下拉刷新用）：拉一次未投递 + 更新未读。
    func refreshInbox() async {
        await receiveOnce(silent: true)
        await loadDevices()
    }

    func loadDevices() async {
        guard let client else { return }
        do {
            devices = try await client.devices()
        } catch {
            self.error = "\(error)"
        }
    }

    // MARK: - 会话与消息

    /// 打开会话。非好友不允许直接开聊——桌面端同样只能从通讯录进入对话，
    /// 否则「加好友」这套流程形同虚设。
    func openChat(_ peer: String) {
        guard isFriend(peer) || !contacts.isEmpty else {
            // 通讯录还没加载完时先放行，避免启动阶段误拦。
            openChatUnchecked(peer)
            return
        }
        guard isFriend(peer) else {
            error = "\(peer) 还不是你的好友，请先在通讯录里申请添加"
            return
        }
        openChatUnchecked(peer)
    }

    private func openChatUnchecked(_ peer: String) {
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
                recallToken: sent.recallToken,
                conversationTag: sent.tag
            ))
            persist()
        } catch {
            if expireSessionIfNeeded(error) {
                draft = ""
                return
            }
            // 失败时把草稿还回去，并明确告诉用户原因（否则看起来像「点了没反应」）。
            draft = text
            self.error = "发送失败：\(error)"
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
                mediaDurationMs: durationMs,
                conversationTag: sent.tag
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

    // MARK: - 找回邮箱

    /// 读取账号资料（拿当前绑定的邮箱）。
    func loadProfile() async {
        guard let client else { return }
        boundEmail = try? await client.profile().email
    }

    /// 绑定 / 修改找回邮箱（需要当前密码）。
    func bindEmail(password: String, email: String) async throws {
        guard let client else { throw MIMApiError.transport("未登录") }
        try await client.bindEmail(password: password, email: email)
        boundEmail = email
        showNotice("找回邮箱已更新为 \(email)")
    }

    /// 发送找回验证码。未登录也要能用，所以现造一个不带令牌的客户端。
    func requestRecoveryCode(username: String) async throws {
        let client = recoveryClient()
        try await client.requestRecoveryCode(username: username)
    }

    func resetPassword(username: String, code: String, newPassword: String) async throws {
        let client = recoveryClient()
        try await client.resetPassword(
            username: username, code: code, newPassword: newPassword
        )
    }

    /// 找回流程用的临时客户端：同一服务端、同一张锁定证书，但不带登录态。
    private func recoveryClient() -> MIMClient {
        let url = URL(string: serverURL) ?? URL(string: "https://127.0.0.1:8443")!
        return MIMClient(
            baseURL: url, deviceId: loadDeviceId(),
            pinnedCertificate: Self.pinnedCertificate()
        )
    }

    // MARK: - 好友

    /// 待我处理的申请（界面红点与列表都用它）。
    var inboundRequests: [MIMKit.FriendRequest] {
        friendRequests.filter { $0.isInbound && $0.isPending }
    }

    func isFriend(_ username: String) -> Bool {
        contacts.contains { $0.username == username }
    }

    /// 拉通讯录 + 申请列表。
    func loadContacts() async {
        guard let client else { return }
        do {
            async let contactsTask = client.contacts()
            async let requestsTask = client.friendRequests()
            let groups = try await contactsTask
            contacts = groups.flatMap(\.users).sorted { $0.username < $1.username }
            friendRequests = try await requestsTask
            for user in contacts.prefix(30) where avatars[user.username] == nil {
                Task { await loadAvatar(user.username) }
            }
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "读取通讯录失败：\(error)"
        }
    }

    /// 搜索用户（空关键字不发请求）。
    func search(_ keyword: String) async {
        let trimmed = keyword.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let client else {
            searchResults = []
            return
        }
        searching = true
        defer { searching = false }
        do {
            searchResults = try await client.searchUsers(trimmed)
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "搜索失败：\(error)"
        }
    }

    /// 发起好友申请。
    func sendFriendRequest(to username: String) async {
        guard let client else { return }
        do {
            try await client.requestFriend(username)
            notice = "已向 \(username) 发送好友申请"
            await loadContacts()
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "发送申请失败：\(error)"
        }
    }

    func acceptRequest(_ request: MIMKit.FriendRequest) async {
        guard let client else { return }
        do {
            try await client.acceptFriendRequest(request.id)
            notice = "已同意 \(request.username) 的好友申请"
            await loadContacts()
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "同意失败：\(error)"
        }
    }

    func rejectRequest(_ request: MIMKit.FriendRequest) async {
        guard let client else { return }
        do {
            try await client.rejectFriendRequest(request.id)
            await loadContacts()
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "拒绝失败：\(error)"
        }
    }

    /// 删除好友（双向，服务端会同步移除双方的会话关系）。
    func removeFriend(_ username: String) async {
        guard let client else { return }
        do {
            try await client.removeContact(username)
            await loadContacts()
            notice = "已删除好友 \(username)"
        } catch {
            if expireSessionIfNeeded(error) { return }
            self.error = "删除好友失败：\(error)"
        }
    }

    // MARK: - 账号安全

    /// 显示一条一次性提示（成功类反馈）。
    func showNotice(_ text: String) {
        notice = text
    }

    /// 统一的错误上报：会话失效时直接回登录页，其余显示带前缀的消息。
    func report(_ error: Error, prefix: String) {
        if expireSessionIfNeeded(error) { return }
        self.error = "\(prefix)：\(error)"
    }

    func twoFactorStatus() async throws -> Bool {
        guard let client else { return false }
        return try await client.twoFactorStatus()
    }

    func setupTwoFactor() async throws -> MIMKit.TwoFactorSetup {
        guard let client else { throw MIMApiError.transport("未登录") }
        return try await client.setupTwoFactor()
    }

    func enableTwoFactor(code: String) async throws -> MIMKit.TwoFactorEnabled {
        guard let client else { throw MIMApiError.transport("未登录") }
        return try await client.enableTwoFactor(code: code)
    }

    func disableTwoFactor(password: String, code: String) async throws {
        guard let client else { throw MIMApiError.transport("未登录") }
        try await client.disableTwoFactor(password: password, code: code)
    }

    /// 修改密码：服务端会作废旧令牌并签发新的，这里立刻换上。
    func changePassword(old: String, new: String) async throws {
        guard let client else { throw MIMApiError.transport("未登录") }
        if let newToken = try await client.changePassword(old: old, new: new), !newToken.isEmpty {
            token = newToken
            saveSession(token: newToken)
        }
    }

    // MARK: - 设备与本地数据

    /// 注销一台设备（不是本机时用）。
    func revokeDevice(_ id: String) async {
        guard let client, id != deviceId else { return }
        do {
            try await client.revokeDevice(id)
            await loadDevices()
            notice = "已注销设备 \(id.prefix(8))…"
        } catch {
            self.error = "注销失败：\(error)"
        }
    }

    /// 重置本机身份：删掉当前账号的密钥包、设备标识与本地库，然后退出登录。
    ///
    /// 用途只有一个但很关键：本机身份与服务端登记的对不上时（例如换账号后
    /// 沿用了上一个账号的身份），对方会一直显示旧名字，只能重新生成身份。
    func resetIdentity() {
        guard !username.isEmpty else { return }
        for base in [Self.identityAccount, Self.deviceIdAccount, Self.publishedAccount] {
            keychain.delete(account: Self.account(base, username))
        }
        try? FileManager.default.removeItem(
            at: MessageVault(root: Self.vaultRoot(), account: username).url
        )
        snapshot = MessageVault.Snapshot()
        messages = []
        conversations = []
        logout()
        notice = "已重置本机身份，请重新登录"
    }

    /// 只清本地聊天记录（身份与登录态保留）。
    ///
    /// 刻意不删 Keychain 里的身份私钥：删了就等于换了一台「新设备」，
    /// 对方的历史同步、已发出的消息归属都会对不上。
    func wipeLocalMessages() {
        snapshot = MessageVault.Snapshot()
        session = nil
        messages = []
        conversations = []
        persist()
        notice = "本地聊天记录已清空"
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
        guard let peer = activePeer else { return }
        await clearConversation(peer)
        closeChat()
    }

    /// 清除与某个联系人的会话（单方）。列表长按与聊天页菜单共用。
    func clearConversation(_ peer: String) async {
        guard let client else { return }
        do {
            try await client.clearConversation(
                peer: peer, tags: session?.tags(for: peer) ?? []
            )
            snapshot.messages.removeAll { $0.peer == peer }
            var readUpTo = snapshot.readUpTo ?? [:]
            readUpTo.removeValue(forKey: peer)
            snapshot.readUpTo = readUpTo
            // 只有正在看这段会话时才需要清空消息列表并退出，
            // 从列表页清除别的会话不该把当前聊天页也清掉。
            if activePeer == peer {
                messages = []
                closeChat()
            }
            refreshConversations()
            persist()
            notice = "已清除与 \(peer) 的会话"
        } catch {
            self.error = "清除会话失败：\(error)"
        }
    }

    /// 把附件解密到一个临时文件，供系统分享面板导出。
    ///
    /// 明文只在临时目录里短暂存在，分享完由系统清理；不写进沙盒的持久目录。
    func exportAttachment(_ message: MessageVault.StoredMessage) async -> URL? {
        guard let id = message.mediaId, let key = message.mediaKey, let vault else { return nil }
        var blob = vault.loadMedia(id)
        if blob == nil, let client {
            blob = try? await client.downloadMedia(id)
            if let blob { vault.saveMedia(id, blob: blob) }
        }
        guard let blob,
              let plain = try? MediaCrypto.decrypt(blob: blob, keyBase64: key) else {
            error = "附件已过期或被清理"
            return nil
        }
        let name = message.mediaName ?? "附件-\(id.prefix(8))"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try plain.write(to: url)
            return url
        } catch {
            self.error = "导出失败：\(error)"
            return nil
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
                let epoch = item.epochMs ?? Int64(Date().timeIntervalSince1970 * 1000)
                if let payload = Self.parseMediaPayload(item.text) {
                    append(MessageVault.StoredMessage(
                        id: item.clientMsgId.isEmpty ? UUID().uuidString.lowercased() : item.clientMsgId,
                        peer: peer, text: payload.text,
                        epochMs: epoch,
                        fromMe: false,
                        kind: payload.kind,
                        mediaId: payload.media.id,
                        mediaMime: payload.media.mime,
                        mediaName: payload.media.name,
                        mediaSize: payload.media.size,
                        mediaKey: payload.media.key,
                        mediaDurationMs: payload.media.durationMs,
                        conversationTag: item.tag,
                        syncedFrom: item.syncedFrom
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
                    // 实时消息用收到时刻（服务端时间是发送方时钟，可能偏）；
                    // 同步副本带的是**原始发送时间**，直接用它，历史顺序才对。
                    epochMs: epoch,
                    fromMe: false,
                    recalled: item.recalled,
                    conversationTag: item.tag,
                    syncedFrom: item.syncedFrom
                ))
            }
            persist()
            if let peer = activePeer, received.contains(where: { $0.peer == peer || $0.peer.isEmpty }) {
                markRead(peer: peer)
            }
        } catch {
            if expireSessionIfNeeded(error) { return }
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
        let socket = MIMWebSocket(
            baseURL: url, token: token, deviceId: deviceId,
            session: client?.transportSession
        )
        pushTask = Task { [weak self] in
            for await event in socket.events() {
                guard let self else { return }
                await self.handle(event)
            }
        }
    }

    private func handle(_ event: PushEvent) async {
        switch event {
        case .connected:
            pushConnected = true
            return
        case .disconnected:
            pushConnected = false
            return
        default:
            pushConnected = true
        }
        lastPush = "\(event)"
        switch event {
        case .connected, .disconnected:
            return
        case .friendChanged:
            await loadContacts()
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
        case .syncRequested(let deviceId):
            await exportHistory(to: deviceId)
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

    /// 按需加载头像：已加载过就跳过，失败保持首字母，不影响列表。
    func loadAvatar(_ name: String) async {
        guard !name.isEmpty, avatars[name] == nil, let client else { return }
        guard let data = try? await client.avatar(name), let image = UIImage(data: data) else {
            return
        }
        avatars[name] = image
    }

    /// 上传自己的头像（PNG 字节）。调用方负责重编码与丢 EXIF。
    func uploadAvatar(_ png: Data) async {
        guard let client, let selfName = token == nil ? nil : username as String? else { return }
        do {
            try await client.uploadAvatar(png)
            if let image = UIImage(data: png) {
                avatars[selfName] = image
            }
            notice = "头像已更新"
        } catch {
            self.error = "头像上传失败：\(error)"
        }
    }

    /// 列表预览文案。媒体消息正文为空，必须给个占位——
    /// 否则会话列表第二行是空白，看起来像坏了。
    static func previewText(_ message: MessageVault.StoredMessage) -> String {
        if !message.text.isEmpty { return message.text }
        switch message.kind {
        case "image": return "[图片]"
        case "voice": return "[语音]"
        case "file": return "[文件] \(message.mediaName ?? "")"
        default: return ""
        }
    }

    /// 应用图标角标：没有未读就清掉。
    private func updateBadge() {
        let unread = totalUnread
        UNUserNotificationCenter.current().setBadgeCount(unread)
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
                    lastText: message.recalled ? "已撤回" : Self.previewText(message),
                    lastEpochMs: message.epochMs,
                    unread: (current?.unread ?? 0) + delta
                )
            } else {
                latest[message.peer]?.unread = (current?.unread ?? 0) + delta
            }
        }
        conversations = latest.values.sorted { $0.lastEpochMs > $1.lastEpochMs }
        updateBadge()
        for peer in conversations.prefix(30).map(\.id) where avatars[peer] == nil {
            Task { await loadAvatar(peer) }
        }
    }

    /// 我发出的这条，对方是否已读。
    ///
    /// 只记「对方读到某时刻」的游标：本地没有服务端消息 id 与消息的严格映射，
    /// 逐条比对 beforeId 会算错；按时间比较在单机会话里是准确的。
    func isReadByPeer(_ message: MessageVault.StoredMessage) -> Bool {
        guard message.fromMe, let readAt = peerReadAt[message.peer] else { return false }
        return readAt >= message.epochMs
    }

    /// 响应另一台设备的历史同步请求：把最近 30 天 / 50 条加密转发过去。
    ///
    /// 与桌面端口径一致：只发文本与附件引用（不搬密文），媒体由对方按 id 自取。
    private func exportHistory(to deviceId: String) async {
        guard let session, let bundle else { return }
        let cutoff = Int64(Date().timeIntervalSince1970 * 1000) - 30 * 24 * 3600 * 1000
        let candidates = snapshot.messages
            .filter { $0.epochMs >= cutoff && !$0.recalled && !$0.peer.isEmpty }
            .sorted { $0.epochMs < $1.epochMs }
            .suffix(50)
            .map { message in
                (id: message.id, peer: message.peer, fromMe: message.fromMe,
                 text: message.text, epochMs: message.epochMs,
                 payload: Self.payloadJSON(for: message),
                 tag: message.conversationTag)
            }
        guard !candidates.isEmpty else { return }
        do {
            let count = try await session.exportHistory(
                to: deviceId,
                messages: Array(candidates),
                identityPrivateHex: bundle.identity.privateKey,
                identityPublicHex: bundle.identity.publicKey
            )
            if count > 0 {
                snapshot.store = session.store
                snapshot.sessions = session.sessions
                persist()
                notice = "已把 \(count) 条历史转发给另一台设备"
            }
        } catch {
            self.error = "转发历史失败：\(error)"
        }
    }

    /// 把本地消息还原成媒体载荷 JSON（同步时原样带过去，对方按 id 自取密文）。
    static func payloadJSON(for message: MessageVault.StoredMessage) -> String? {
        guard let mediaId = message.mediaId, let key = message.mediaKey else { return nil }
        let payload = MediaPayload(
            kind: message.kind,
            text: message.text,
            media: MediaRef(
                id: mediaId, key: key,
                size: message.mediaSize ?? 0,
                mime: message.mediaMime ?? "application/octet-stream",
                name: message.mediaName,
                width: nil, height: nil,
                durationMs: message.mediaDurationMs
            )
        )
        guard let data = try? JSONEncoder().encode(payload) else { return nil }
        return String(decoding: data, as: UTF8.self)
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

    /// 随包分发的自签证书（调试期 SSL 锁定用）。
    ///
    /// 用锁定代替「在手机上装描述文件 + 打开完全信任」：少一步人工操作，
    /// 也不会因为漏点那个开关而得到一句莫名其妙的「无法连接」。
    static func pinnedCertificate() -> Data? {
        guard let url = Bundle.main.url(forResource: "mim-local-ca", withExtension: "cer") else {
            return nil
        }
        return try? Data(contentsOf: url)
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
        try? keychain.set(Data("1".utf8), account: Self.account(Self.publishedAccount, username))
    }

    /// 校准「本地身份」与「服务端登记的指纹」。
    ///
    /// 两者不一致时的现象很迷惑：**对方联系人列表里显示的是旧账号的名字**，
    /// 因为对方是按身份公钥认人的。发现不一致就用当前密码重新发布公钥。
    private func reconcilePublishedIdentity(
        client: MIMClient, bundle: KeyBundle, password: String
    ) async throws {
        let keys = try await client.peerDevices(username)
        let mine = keys.first(where: { $0.deviceId == client.deviceId })
        let local = Self.fullFingerprint(ofHex: bundle.identity.publicKey)
        let remote = (mine?.fingerprint ?? "").uppercased()
        // 三种情况都要重发：服务端没有这台设备、没有公钥、或指纹与本地不一致。
        // 少发一次，对方就查不到���密钥，表现为「消息发不出去/对方收不到」。
        guard remote != local else { return }
        try await client.publishKeys(
            password: password, deviceName: UIDevice.current.name, bundle: bundle
        )
        notice = "本机身份与服务端不一致，已重新发布公钥"
    }

    /// SPKI DER 的 SHA-256（大写十六进制，无分隔）。
    static func fullFingerprint(ofHex hex: String) -> String {
        SHA256.hash(data: Data(hexString: hex)).map { String(format: "%02X", $0) }.joined()
    }

    /// 本机身份密钥包：首次登录生成并只写 Keychain。
    @discardableResult
    private func ensureIdentityBundle() throws -> KeyBundle {
        var bundle: KeyBundle
        let account = Self.account(Self.identityAccount, username)
        if let data = keychain.get(account: account),
           let stored = try? JSONDecoder().decode(KeyBundle.self, from: data) {
            bundle = stored
        } else {
            bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 20)
            let data = try JSONEncoder().encode(bundle)
            try keychain.set(data, account: account)
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
