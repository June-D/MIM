import SwiftUI

/// 应用入口。M1 阶段只有「登录 → 设备列表」两个界面，
/// 目的是先把 登录 / Keychain / 密钥包 这条链路在真机上跑通。
@main
struct MIMiOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            if model.token == nil {
                LoginView()
            } else if model.activePeer != nil {
                ChatView()
            } else {
                ConversationListView()
            }
        }
        .task {
            // 冷启动先尝试恢复上次登录态：令牌有效就直接进会话列表，
            // 无效则静默回到登录页（不弹错误，用户会当成正常启动）。
            await model.restoreSession()
        }
    }
}
