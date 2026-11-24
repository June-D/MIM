import Foundation

/// 会话索引的反查工具。
///
/// 会话索引的键是 `对端用户名|对端设备 id`，值是 `SessionRef{sessionId, tag}`。
/// 推送里只带 `conversation_tag`（随机串，不含身份信息），要在这里反查回「跟谁在聊」。
public enum SessionIndex {

    /// 按会话标签找对端用户名。
    ///
    /// 同一个对端可能有多台设备（多个标签），任意一条命中就够——用户点开的是会话，
    /// 不是某台设备。
    public static func peer(forTag tag: String, in sessions: [String: SessionRef]) -> String? {
        guard !tag.isEmpty else { return nil }
        for (key, ref) in sessions where ref.tag == tag {
            // 键格式固定为 "peer|deviceId"，peer 里不会有 "|"（用户名有校验）。
            guard let peer = key.split(separator: "|", maxSplits: 1).first, !peer.isEmpty else {
                continue
            }
            return String(peer)
        }
        return nil
    }
}
