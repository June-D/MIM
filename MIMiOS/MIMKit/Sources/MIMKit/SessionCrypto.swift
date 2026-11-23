import Foundation
import MIMCoreABI

/// 会话层：首条消息之后的收发、以及会话存储的维护。
///
/// 与 `MimCore` 分开是因为这些调用的参数是「整份会话存储」，需要原样往返
/// （`JSONValue`），而 MimCore 只处理固定结构的 DTO。
public enum SessionCrypto {

    private struct InsertRequest: Encodable {
        let store: JSONValue
        let sessionId: String
        let peer: String
        let deviceId: String
        let state: StateSnapshot
        let tags: [String]
    }

    private struct ComposeSealedRequest: Encodable {
        let store: JSONValue
        let sessionId: String
        let plaintext: String
    }

    private struct ComposeSealedResponse: Decodable {
        let store: JSONValue
        let wire: WireMessage
    }

    private struct OpenSealedRequest: Encodable {
        let store: JSONValue
        let sessionId: String
        let wire: WireMessage
    }

    private struct OpenSealedResponse: Decodable {
        let store: JSONValue
        let plaintext: String
    }

    private struct StoreResponse: Decodable {
        let store: JSONValue
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    /// 把会话状态并入存储（按 peer + device + tag 索引）。
    public static func insert(
        store: JSONValue,
        sessionId: String,
        peer: String,
        deviceId: String,
        state: StateSnapshot,
        tags: [String]
    ) throws -> JSONValue {
        let payload = try encoder.encode(InsertRequest(
            store: store, sessionId: sessionId, peer: peer,
            deviceId: deviceId, state: state, tags: tags
        ))
        let data = try FFI.call("写入会话存储", payload, mim_session_store_insert)
        return try decoder.decode(StoreResponse.self, from: data).store
    }

    /// 会话内加密一条消息。
    public static func composeSealed(
        store: JSONValue,
        sessionId: String,
        plaintext: String
    ) throws -> (store: JSONValue, wire: WireMessage) {
        let payload = try encoder.encode(ComposeSealedRequest(
            store: store, sessionId: sessionId, plaintext: plaintext
        ))
        let data = try FFI.call("加密消息", payload, mim_compose_sealed)
        let response = try decoder.decode(ComposeSealedResponse.self, from: data)
        return (response.store, response.wire)
    }

    /// 解开会话内消息。
    public static func openSealed(
        store: JSONValue,
        sessionId: String,
        wire: WireMessage
    ) throws -> (store: JSONValue, plaintext: String) {
        let payload = try encoder.encode(OpenSealedRequest(
            store: store, sessionId: sessionId, wire: wire
        ))
        let data = try FFI.call("解密消息", payload, mim_open_sealed)
        let response = try decoder.decode(OpenSealedResponse.self, from: data)
        return (response.store, response.plaintext)
    }
}
