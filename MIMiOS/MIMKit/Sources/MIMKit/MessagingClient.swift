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

    /// 上传媒体密文（`POST /media`，octet-stream），返回媒体 id。
    ///
    /// 超过 8MB 时走分片（服务端 `init/chunk/complete`）——大图/文件不撑爆单请求。
    public func uploadMedia(_ blob: Data) async throws -> String {
        if blob.count <= 8 * 1024 * 1024 {
            var request = URLRequest(url: url(for: "/media"))
            request.httpMethod = "POST"
            request.setValue("Bearer \(currentToken ?? "")", forHTTPHeaderField: "Authorization")
            request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.httpBody = blob
            let (data, response) = try await rawSend(request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw MIMApiError.transport("媒体上传失败")
            }
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            let envelope = try decoder.decode(MIMEnvelope<MediaUploadAck>.self, from: data)
            guard envelope.status == 200, let id = envelope.data?.id, !id.isEmpty else {
                throw MIMApiError.failed(envelope.status, envelope.message ?? "媒体上传失败")
            }
            return id
        }
        return try await uploadMediaChunked(blob)
    }

    /// 下载媒体密文。
    public func downloadMedia(_ id: String) async throws -> Data {
        var request = URLRequest(url: url(for: "/media/\(id)"))
        request.setValue("Bearer \(currentToken ?? "")", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        let (data, response) = try await rawSend(request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw MIMApiError.transport("媒体下载失败")
        }
        return data
    }

    /// 分片上传（>8MB）：init → 逐片 PUT → complete。
    private func uploadMediaChunked(_ blob: Data) async throws -> String {
        struct InitRequest: Encodable {
            let size: Int64
            let chunkSize: Int
        }
        let chunkSize = 1024 * 1024
        let initEnvelope: MIMEnvelope<ChunkInitAck> = try await send(
            method: "POST", path: "/media/upload/init",
            body: InitRequest(size: Int64(blob.count), chunkSize: chunkSize)
        )
        guard initEnvelope.status == 200, let uploadId = initEnvelope.data?.uploadId else {
            throw MIMApiError.failed(initEnvelope.status, initEnvelope.message ?? "无法开始分片上传")
        }
        var offset = 0
        var index = 0
        while offset < blob.count {
            let end = min(offset + chunkSize, blob.count)
            var request = URLRequest(url: url(for: "/media/upload/\(uploadId)/\(index)"))
            request.httpMethod = "PUT"
            request.setValue("Bearer \(currentToken ?? "")", forHTTPHeaderField: "Authorization")
            request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.httpBody = blob.subdata(in: offset..<end)
            let (data, response) = try await rawSend(request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                let text = String(data: data, encoding: .utf8) ?? ""
                throw MIMApiError.transport("分片 \(index) 上传失败：\(text.prefix(120))")
            }
            offset = end
            index += 1
        }
        let done: MIMEnvelope<MediaUploadAck> = try await send(
            method: "POST", path: "/media/upload/\(uploadId)/complete"
        )
        guard done.status == 200, let id = done.data?.id, !id.isEmpty else {
            throw MIMApiError.failed(done.status, done.message ?? "合并分片失败")
        }
        return id
    }

    /// 撤回自己发出的一条消息（按 clientMsgId，撤掉所有设备副本）。
    ///
    /// `recallToken` 是发送时生成的一次性凭证，服务端只存它的哈希——
    /// 没有它撤不掉，也不能撤回别人的消息。
    public func recallMessage(clientMsgId: String, recallToken: String,
                              tags: [String]) async throws {
        struct Request: Encodable {
            let recallToken: String
            let tags: [String]
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/message/recall/\(clientMsgId)",
            body: Request(recallToken: recallToken, tags: tags)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "撤回失败")
        }
    }

    /// 清除与某人的会话（单方）。
    ///
    /// `forBoth` 为 true 时对方那边也会消失——这需要服务端按标签抹密文，
    /// 而 iOS 侧没有会话标签，所以客户端只用单方清除，不假装支持双向。
    public func clearConversation(peer: String, tags: [String]) async throws {
        struct Request: Encodable {
            let peer: String
            let forBoth: Bool
            let tags: [String]
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/message/clear",
            body: Request(peer: peer, forBoth: false, tags: tags)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "清除会话失败")
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
