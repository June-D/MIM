import UIKit
import UserNotifications

/// 推送接入（极光 JPush）。
///
/// SwiftUI 的 App 没有 AppDelegate，但极光 SDK 依赖
/// `didRegisterForRemoteNotificationsWithDeviceToken` 这类回调，
/// 所以用 `@UIApplicationDelegateAdaptor` 挂一个。
///
/// 注意：JPush 6.x 的 SwiftPM 包**没有导出 Swift 模块**，靠 `Sources/MIMiOS-Bridging-Header.h`
/// 引入 `JPUSHService.h`——所以这里不需要 `import JPush`。
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// 拿到 registrationID 后交给 AppModel 上报服务端。
    ///
    /// 用静态闭包而不是直接引用 AppModel：AppDelegate 生命周期更长，
    /// 而且它不该知道网络层怎么实现。
    static var onRegistration: ((String) -> Void)?

    /// 用户点开通知：把 extras 交给 AppModel，由它反查出会话并打开。
    static var onNotificationTap: (([AnyHashable: Any]) -> Void)?

    /// 冷启动时通知先到、AppModel 还没准备好，先存下来等它挂上回调再消费。
    static var pendingTap: [AnyHashable: Any]?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // AppKey 从 Info.plist 读（project.yml 的 JPushAppKey）。留空就整体跳过，
        // 没配推送的环境照样能编译、启动、收发消息。
        // 通知的点击/前台展示由我们自己的 delegate 处理：
        // 极光那几个回调在 Swift 下签名对不上（见下方注释），而系统这套是标准 API。
        UNUserNotificationCenter.current().delegate = self

        guard let appKey = Bundle.main.object(forInfoDictionaryKey: "JPushAppKey") as? String,
              !appKey.isEmpty else {
            return true
        }

        let entity = JPUSHRegisterEntity()
        entity.types = Int(
            JPAuthorizationOptions.alert.rawValue
                | JPAuthorizationOptions.badge.rawValue
                | JPAuthorizationOptions.sound.rawValue
        )
        // delegate 传 nil：极光那几个回调的 Swift 签名带隐式解包与 NSInteger→
        // UNAuthorizationOptions 的隐式转换，声明成强类型的 Swift 方法反而
        // 不满足 @objc 协议一致性。前台横幅由系统默认行为负责，
        // App 在前台时本来就靠 WebSocket 实时收消息，不依赖推送。
        JPUSHService.register(forRemoteNotificationConfig: entity, delegate: nil)
        JPUSHService.setup(
            withOption: launchOptions,
            appKey: appKey,
            channel: "App Store",
            // 开发期走 APNs sandbox；上架后改成 true，并与服务端
            // MIM_JPUSH_APNS_PRODUCTION 保持一致。
            apsForProduction: false
        )

        // registrationID 是异步拿到的：先主动取一次，再挂回调兜住「稍后才到」的情况。
        JPUSHService.registrationIDCompletionHandler { resCode, registrationID in
            guard resCode == 0, let registrationID, !registrationID.isEmpty else {
                // 模拟器没有 APNs 令牌，这里会拿到 1011 —— 属正常。
                print("[push] 暂未拿到 registrationID（resCode=\(resCode)）")
                return
            }
            Self.onRegistration?(registrationID)
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        JPUSHService.registerDeviceToken(deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // 模拟器、或证书没配好都会走到这里；不影响 App 其它功能。
        print("[push] 注册 APNs 失败：\(error.localizedDescription)")
    }

    // MARK: - 通知交互

    /// 前台也展示横幅：否则用户在聊天列表页时会以为没收到提醒。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    /// 点开通知：取出 extras（含 `tag`），让 AppModel 打开对应会话。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let handler = Self.onNotificationTap {
            handler(info)
        } else {
            Self.pendingTap = info
        }
        completionHandler()
    }
}
