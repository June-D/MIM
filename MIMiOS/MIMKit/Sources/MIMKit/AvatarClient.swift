import Foundation

/// 头像（服务端下发的 PNG）。
///
/// 头像**不是端到端加密的**：它必须给联系人看，服务端要能直接返回字节。
/// 所以这里明确区分：消息走加密通道，头像走普通 HTTP——不要混在同一条路径里想。
extension MIMClient {

    /// 读取某个用户的头像；没有头像（404）时返回 nil，让界面回落到首字母。
    public func avatar(_ username: String) async throws -> Data? {
        let encoded = username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? username
        var request = URLRequest(url: url(for: "/user/avatar/\(encoded)"))
        request.setValue("Bearer \(currentToken ?? "")", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")

        let (data, response) = try await rawSend(request)
        guard let http = response as? HTTPURLResponse else { return nil }
        if http.statusCode == 404 {
            // 没设置头像不是错误，是常态。
            return nil
        }
        guard (200..<300).contains(http.statusCode), !data.isEmpty else {
            throw MIMApiError.transport("头像读取失败（HTTP \(http.statusCode)）")
        }
        return data
    }

    /// 上传头像（PNG，≤512KB）。调用方负责先重编码成 PNG 并丢掉 EXIF。
    public func uploadAvatar(_ png: Data) async throws {
        struct Request: Encodable {
            let image: String
        }
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "POST", path: "/user/avatar",
            body: Request(image: png.base64EncodedString())
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "头像上传失败")
        }
    }

    /// 删除头像。
    public func deleteAvatar() async throws {
        let envelope: MIMEnvelope<IgnoredPayload> = try await send(
            method: "DELETE", path: "/user/avatar"
        )
        guard envelope.status == 200 else {
            throw MIMApiError.failed(envelope.status, envelope.message ?? "头像删除失败")
        }
    }
}
