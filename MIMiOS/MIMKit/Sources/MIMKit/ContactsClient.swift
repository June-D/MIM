import Foundation

/// 好友体系的服务端 DTO（camelCase，与 Spring 侧一致）。
public struct ChatUser: Decodable, Equatable, Sendable, Identifiable {
    public let username: String
    /// 我给对方设的备注名，优先于昵称显示。
    public let labelName: String?
    public let nickname: String?
    public let pic: String?
    public let online: Bool?

    public var id: String { username }
    /// 界面显示名：备注 > 昵称 > 用户名。
    public var displayName: String {
        if let labelName, !labelName.isEmpty { return labelName }
        if let nickname, !nickname.isEmpty { return nickname }
        return username
    }
}

public struct ChatContact: Decodable, Equatable, Sendable {
    public let groupId: String?
    public let groupName: String?
    public let users: [ChatUser]
}

public struct UserSummary: Decodable, Equatable, Sendable, Identifiable {
    public let username: String
    public let nickname: String?
    public let pic: String?
    public let signature: String?
    /// 对方是否已建立加密身份（false 表示很久没登录过，发消息也收不到）。
    public let canReceive: Bool?

    public var id: String { username }
}

public struct FriendRequest: Decodable, Equatable, Sendable, Identifiable {
    public static let inbound = "in"
    public static let accepted = "accepted"
    public static let pending = "pending"

    public let id: Int64
    /// 对方用户名
    public let username: String
    /// in = 待我处理；out = 我发出的
    public let direction: String
    /// pending / accepted / rejected
    public let status: String

    public var isInbound: Bool { direction == FriendRequest.inbound }
    public var isPending: Bool { status == FriendRequest.pending }
}

/// 好友与申请。桌面端同一套接口，移动端不需要服务端做任何改动。
extension MIMClient {

    /// 联系人列表（服务端按分组返回）。
    ///
    /// 注意：这个接口**直接返回数组**，不套 `{status, data}` 信封——
    /// 用通用 `send` 会报「响应不是预期的 JSON」。这里按原始响应解析。
    public func contacts() async throws -> [ChatContact] {
        var request = URLRequest(url: url(for: "/chat/contacts"))
        request.setValue("Bearer \(currentToken ?? "")", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        let (data, response) = try await rawSend(request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw MIMApiError.transport("读取通讯录失败（GET /chat/contacts）")
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        do {
            return try decoder.decode([ChatContact].self, from: data)
        } catch {
            throw MIMApiError.transport("通讯录不是预期的 JSON")
        }
    }

    /// 搜索可添加的用户（服务端已排除自己与已是好友的人）。
    public func searchUsers(_ keyword: String) async throws -> [UserSummary] {
        let encoded = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        let envelope: MIMEnvelope<[UserSummary]> = try await send(
            method: "GET", path: "/chat/search?keyword=\(encoded)"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "搜索失败")
        }
        return envelope.data ?? []
    }

    /// 发起好友申请。对方同意前不会建立联系人关系。
    @discardableResult
    public func requestFriend(_ username: String) async throws -> FriendRequest? {
        struct Request: Encodable {
            let username: String
        }
        let envelope: MIMEnvelope<FriendRequest> = try await send(
            method: "POST", path: "/chat/requests", body: Request(username: username)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "发送好友申请失败")
        }
        return envelope.data
    }

    /// 待处理的申请：我收到的（in）与我发出的（out）。
    public func friendRequests() async throws -> [FriendRequest] {
        let envelope: MIMEnvelope<[FriendRequest]> = try await send(
            method: "GET", path: "/chat/requests"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "读取好友申请失败")
        }
        return envelope.data ?? []
    }

    public func acceptFriendRequest(_ id: Int64) async throws {
        try await decide(path: "/chat/requests/\(id)/accept")
    }

    public func rejectFriendRequest(_ id: Int64) async throws {
        try await decide(path: "/chat/requests/\(id)/reject")
    }

    /// 删除好友（双向）。
    public func removeContact(_ peer: String) async throws {
        let encoded = peer.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? peer
        let envelope: MIMEnvelope<Bool> = try await send(
            method: "DELETE", path: "/chat/contacts/\(encoded)"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "删除好友失败")
        }
    }

    /// 改备注名（传空串即清除）。
    public func renameContact(_ peer: String, label: String) async throws {
        struct Request: Encodable {
            let username: String
            let labelName: String
        }
        let encoded = peer.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? peer
        let envelope: MIMEnvelope<Bool> = try await send(
            method: "POST", path: "/chat/contacts/\(encoded)/label",
            body: Request(username: peer, labelName: label)
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "修改备注失败")
        }
    }

    private func decide(path: String) async throws {
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(method: "POST", path: path)
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "操作失败")
        }
    }
}
