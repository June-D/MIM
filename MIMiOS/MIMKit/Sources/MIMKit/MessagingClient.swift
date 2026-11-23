import Foundation

/// 消息相关的 HTTP 接口（与桌面端 `SecureBackend` 一一对应）。
extension MIMClient {

    /// 对端账号下的所有有效设备。多设备下发时必须为每个设备各加密一份。
    public func peerDevices(_ username: String) async throws -> [PeerKey] {
        let envelope: MIMEnvelope<[PeerKey]> = try await send(method: "GET", path: "/key/\(username)")
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, "\(envelope.message ?? "查询对方公钥失败")（GET /key/\(username)）")
        }
        return envelope.data ?? []
    }

    /// 某台设备当前可用的预密钥（建首条消息用）。
    public func preKeys(_ username: String, deviceId: String) async throws -> PreKeyBundle {
        let encoded = deviceId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? deviceId
        let path = "/prekey/\(username)?deviceId=\(encoded)"
        // 服务端即使带 deviceId 也返回列表（便于多设备扩展），这里取该设备的那一份。
        let envelope: MIMEnvelope<[PreKeyBundle]> = try await send(method: "GET", path: path)
        guard envelope.status == 200, let bundle = envelope.data?.first(where: { $0.deviceId == deviceId })
            ?? envelope.data?.first else {
            throw MIMApiError.failed(envelope.status, "\(envelope.message ?? "对方没有可用预密钥")（GET /prekey/\(username)）")
        }
        return bundle
    }

    /// 为本设备签发发送者证书（首条消息的信封里带它）。
    public func issueCertificate() async throws -> SenderCertificate {
        let envelope: MIMEnvelope<SenderCertificate> = try await send(method: "POST", path: "/cert/sender")
        guard envelope.status == 200, let certificate = envelope.data else {
            throw MIMApiError.failed(envelope.status, "\(envelope.message ?? "签发证书失败")（POST /cert/sender）")
        }
        return certificate
    }

    /// 提交密文副本（每个目标设备一条）。
    public func sendReplicas(_ copies: [OutgoingCopy]) async throws {
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/message", body: copies
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, "\(envelope.message ?? "发送失败")（POST /message/send）")
        }
    }

    /// 拉取未投递的密文（拉过即标记已投递，不会重复下发）。
    public func undelivered() async throws -> [IncomingCopy] {
        let envelope: MIMEnvelope<[IncomingCopy]> = try await send(
            method: "GET", path: "/message/undelivered"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, "\(envelope.message ?? "拉取消息失败")（GET /message/undelivered）")
        }
        return envelope.data ?? []
    }

    /// 确认收到：把本设备 `beforeId` 之前的消息标记为已投递。
    ///
    /// **必须调用**。服务端拉取不会自动置已投递，不确认的话同一条密文每轮都会
    /// 再下发一次——界面上表现为消息重复、时间戳不断刷新。
    public func markDelivered(beforeId: Int64) async throws {
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/message/delivered?beforeId=\(beforeId)"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "确认投递失败")
        }
    }

    /// 标记已读（账号级语义：同一逻辑消息在本人各设备上一起置已读）。
    ///
    /// 必须带 `clientMsgIds`——各设备副本的会话标签不同，只按 tags 只能命中自己那份。
    public func markRead(peer: String, clientMsgIds: [String]) async throws {
        guard !clientMsgIds.isEmpty else { return }
        struct Request: Encodable {
            let peer: String
            let clientMsgIds: [String]
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/message/read",
            body: Request(peer: peer, clientMsgIds: Array(clientMsgIds.suffix(200)))
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "标记已读失败")
        }
    }

    /// 会话历史（服务端只存密文；能解开的靠本地会话状态与会话内密钥）。
    public func history(peer: String, tags: [String], limit: Int = 50) async throws -> [IncomingCopy] {
        guard !tags.isEmpty else { return [] }
        let joined = tags.joined(separator: ",")
        let envelope: MIMEnvelope<[IncomingCopy]> = try await send(
            method: "GET", path: "/message/history/\(peer)?limit=\(limit)&tags=\(joined)"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "拉取历史失败")
        }
        return envelope.data ?? []
    }
}
