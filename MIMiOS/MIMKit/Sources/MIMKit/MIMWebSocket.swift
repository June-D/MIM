import Foundation

/// 下行推送（服务端只用来「叫醒」HTTP 同步，不承载业务判断）。
public enum PushEvent: Sendable, Equatable {
    /// 新消息 / 已读回执 / 撤回：都表现为「去拉一次」。
    case newData
    /// 对方读了我的消息；payload 带 peer（读的人）。
    case readReceipt(peer: String)
    /// 对方清除了会话。
    case cleared(peer: String)
    /// 同账号另一台设备读了消息。
    case readSync(peer: String)
    /// 同账号另一台设备请求历史同步。
    case syncRequested(deviceId: String)
}

/// 长连接：`/websocket/compose`，401/403 就重连并退避。
///
/// 与桌面端一致的三条约定：
/// - 认证走 header（`Authorization` + `X-Device-Id`），不放进 URL——URL 会进日志；
/// - 25 秒没有帧就发 `{"ping":true}` 心跳，服务端按报文里是否含 ping 识别；
/// - 断线后 3 秒重连，不做指数退避（服务端重启时希望尽快回来）。
public final class MIMWebSocket: @unchecked Sendable {

    private let baseURL: URL
    private let token: String
    private let deviceId: String
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var running = false

    public init(baseURL: URL, token: String, deviceId: String) {
        self.baseURL = baseURL
        self.token = token
        self.deviceId = deviceId
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: configuration)
    }

    /// 事件流。调用方 `for await` 消费；取消消费任务即断开。
    public func events() -> AsyncStream<PushEvent> {
        AsyncStream { continuation in
            let worker = Task { [weak self] in
                guard let self else { return }
                self.running = true
                while self.running && !Task.isCancelled {
                    await self.runOnce { event in
                        continuation.yield(event)
                    }
                    if Task.isCancelled || !self.running { break }
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in
                worker.cancel()
                self.stop()
            }
        }
    }

    public func stop() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // MARK: - 连接

    private func endpoint() -> URL? {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = baseURL.scheme == "https" ? "wss" : "ws"
        components?.path = "/websocket/compose"
        components?.query = nil
        return components?.url
    }

    /// 一次连接生命周期：连上就一直读，断了返回，由外层重连。
    private func runOnce(onEvent: @escaping (PushEvent) -> Void) async {
        guard let url = endpoint() else { return }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")

        let socket = session.webSocketTask(with: request)
        task = socket
        socket.resume()

        // 心跳与读循环并行；任一结束就整体退出，交给外层重连。
        let heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                if Task.isCancelled { return }
                self?.send(socket, "{\"ping\":true}")
            }
        }
        defer {
            heartbeat.cancel()
            socket.cancel(with: .goingAway, reason: nil)
        }

        while !Task.isCancelled && running {
            do {
                let message = try await socket.receive()
                switch message {
                case .string(let text):
                    if let event = Self.parse(text) { onEvent(event) }
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8),
                       let event = Self.parse(text) {
                        onEvent(event)
                    }
                @unknown default:
                    break
                }
            } catch {
                return
            }
        }
    }

    private func send(_ socket: URLSessionWebSocketTask, _ text: String) {
        socket.send(.string(text)) { _ in }
    }

    // MARK: - 解析

    private static let typeNewMessage = 100010
    /// 已读回执是 **100012**（100011 是旧编号，保留兼容）。
    private static let typeReadReceipt = 100012
    private static let typeReadReceiptLegacy = 100011
    private static let typeRecalled = 100014
    private static let typeCleared = 100019
    private static let typeHistorySync = 100022
    private static let typeReadSync = 100024

    static func parse(_ text: String) -> PushEvent? {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? Int else {
            return nil
        }
        let payload = object["data"] as? [String: Any]
        func peer() -> String {
            (payload?["peer"] as? String) ?? ""
        }
        switch type {
        case typeNewMessage, typeRecalled:
            return .newData
        case typeReadReceipt, typeReadReceiptLegacy:
            return .readReceipt(peer: peer())
        case typeCleared:
            return .cleared(peer: peer())
        case typeReadSync:
            return .readSync(peer: peer())
        case typeHistorySync:
            let device = (payload?["deviceId"] as? String) ?? ""
            return device.isEmpty ? nil : .syncRequested(deviceId: device)
        default:
            return nil
        }
    }
}
