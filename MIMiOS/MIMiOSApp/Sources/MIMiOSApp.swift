import SwiftUI

/// 应用入口。
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

/// 根视图：未登录看登录页；登录后是三个标签页（会话 / 通讯录 / 我）。
///
/// 移动端与桌面端的功能一一对应，但组织方式不同：桌面是「侧栏 + 主区」，
/// 移动端用标签栏，把「发起对话」收进通讯录——和微信/iMessage 的心智一致。
struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if model.token == nil {
                NavigationStack { LoginView() }
            } else {
                TabView(selection: $model.selectedTab) {
                    NavigationStack {
                        if model.activePeer != nil {
                            ChatView()
                        } else {
                            ConversationListView()
                        }
                    }
                    .tabItem { Label("会话", systemImage: "bubble.left.and.bubble.right") }
                    .badge(model.totalUnread)
                    .tag(0)

                    NavigationStack { ContactsView() }
                        .tabItem { Label("通讯录", systemImage: "person.2") }
                        .badge(model.inboundRequests.count)
                        .tag(1)

                    NavigationStack { ProfileView() }
                        .tabItem { Label("我", systemImage: "person.crop.circle") }
                    .tag(2)
                }
            }
        }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.environment["MIM_DEMO_LOGOUT"] == "1" {
                model.debugLogout()
            }
            #endif
            // 冷启动先尝试恢复上次登录态：令牌有效就直接进会话列表，
            // 无效则静默回到登录页（不弹错误，用户会当成正常启动）。
            await model.restoreSession()
            #if DEBUG
            if let tab = ProcessInfo.processInfo.environment["MIM_DEMO_TAB"],
               let index = Int(tab) {
                model.selectedTab = index
            }
            #endif
        }
    }
}
