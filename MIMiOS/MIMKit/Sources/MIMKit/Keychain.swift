import Foundation
import Security

/// Keychain 读写。
///
/// iOS 与 macOS 用同一套 Security API；这里只做「按 account 存一段 Data」，
/// 具体存什么由调用方决定（身份私钥、设备标识、本地状态密钥）。
///
/// 访问策略固定为 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
/// - AfterFirstUnlock：重启后不需解锁即可后台收消息；
/// - ThisDeviceOnly：不进 iCloud Keychain、不随备份迁移到别的设备。
public struct Keychain: Sendable {

    public enum Failure: Error, Equatable {
        case unhandled(OSStatus)
    }

    public let service: String

    public init(service: String = "ai.mim.app") {
        self.service = service
    }

    private func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    public func set(_ data: Data, account: String) throws {
        var attributes = query(account: account)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        // 先删再写：SecItemUpdate 在「条目不存在」时返回 errSecItemNotFound，
        // 统一成一次删除 + 一次新增，语义更直白。
        SecItemDelete(attributes as CFDictionary)
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw Failure.unhandled(status)
        }
    }

    public func get(account: String) -> Data? {
        var attributes = query(account: account)
        attributes[kSecReturnData as String] = true
        attributes[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(attributes as CFDictionary, &result)
        guard status == errSecSuccess else {
            return nil
        }
        return result as? Data
    }

    @discardableResult
    public func delete(account: String) -> Bool {
        SecItemDelete(query(account: account) as CFDictionary) == errSecSuccess
    }

    /// 删除该 service 下的所有条目（本机自毁 / 退出登录时用）。
    ///
    /// iOS 卸载 App 时 Keychain 默认保留，所以「重装后清孤儿密钥」必须显式调用。
    @discardableResult
    public func deleteAll() -> Bool {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        return SecItemDelete(attributes as CFDictionary) == errSecSuccess
    }
}
