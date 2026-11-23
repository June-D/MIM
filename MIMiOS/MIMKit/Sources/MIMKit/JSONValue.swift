import Foundation

/// 任意 JSON 的容器。
///
/// 用来透传会话存储（`SessionStore`）：Swift 不需要理解它内部结构，
/// 只要原样存、原样传回 Rust。不理解的东西就不要解析，避免结构一改就崩。
public enum JSONValue: Codable, Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    /// 空会话存储。
    ///
    /// Rust 的 `SessionStore` 是 `{ sessions: Vec<_> }`，**没有** `serde(default)`；
    /// 传 `{}` 会反序列化失败（表现为 `invalidArgument`）。这里必须给出完整形状。
    public static var emptyStore: JSONValue { .object(["sessions": .array([])]) }
}
