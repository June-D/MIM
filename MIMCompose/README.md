# MIMCompose

移动端 UI 骨架（Compose Multiplatform）。

## 定位

这是客户端界面的**结构样板**，不是完整客户端：

- 已有：登录 → 联系人 → 聊天三屏，状态集中管理，可运行的桌面目标
- 未接：真实 REST / WebSocket 传输、真实协议与密码学（属于 `mim-core` 与 `MIMCoreRust`）

这样分层的目的：UI 与状态先稳定，传输和密码学各自独立演进，互不阻塞。

## 目录

```
MIMCompose/
  composeApp/src/desktopMain/kotlin/com/junesoon/mim/app/
    Main.kt               窗口入口（920×660）
    MimApp.kt             路由分发，只读状态
    domain/Model.kt       Contact / ChatMessage
    data/MessagingBackend.kt  传输层契约（登录/联系人/历史/发送）
    data/DemoBackend.kt   内存演示实现（含回声，仅用于跑通 UI）
    state/AppStore.kt     唯一状态源：StateFlow + 动作
    ui/Theme.kt           纸墨配色，换主题只改这一处
    ui/LoginScreen.kt
    ui/ContactsScreen.kt
    ui/ChatScreen.kt
  composeApp/src/desktopTest/kotlin/  状态流单元测试
```

## 运行

```bash
cd MIMCompose
./gradlew :composeApp:run          # 演示后端（内存）
./gradlew :composeApp:desktopTest  # 状态流单元测试

# 接真实服务端：登录与联系人走 HTTP
MIM_SERVER_URL=http://127.0.0.1:8080 ./gradlew :composeApp:run
MIM_SERVER_URL=http://127.0.0.1:8080 ./gradlew :composeApp:desktopTest
```

首次构建会下载 Gradle 与 Compose 依赖。国内网络可加 `MIM_GRADLE_MIRRORS=1`
启用 Maven 镜像。

### 当前接入范围

| 能力 | 演示后端 | 真实服务端（HttpBackend） |
|---|---|---|
| 登录 | 模拟 | `POST /login/token`，令牌绑定设备 |
| 联系人 | 固定两条 | `GET /chat/contacts`（分组展开） |
| 聊天历史 | 内存 | **未接入**（返回空） |
| 发送 | 内存回声 | **未接入**（显式报错，不造假数据） |

历史与发送需要端到端加密链路，是协议层迁移的下一步。

## 接 Android / iOS

1. **Android**：在 `composeApp/build.gradle.kts` 的 `kotlin { }` 里加
   `androidTarget()`，把 `desktopMain` 的 UI/状态代码移到 `commonMain`
   （这些文件本来就没有桌面 API），再补 `androidMain` 的入口 `Activity`。
   Rust 核心用 `MIMCoreRust` 的 JNI 绑定（`MimCoreNative`），把 `.so` 放进
   `jniLibs`。
2. **iOS**：Rust 核心编译为静态库 / xcframework，经 `cinterop` 暴露
   `include/mim_core.h` 的函数；UI 复用 `commonMain`。

## 约束

- UI 不接触明文密钥；状态由 Rust 核心的 `secure_store` 加密落盘，
  密钥来自 Android Keystore / iOS Keychain。
- 演示后端只用于验证界面与状态流，不代表协议行为。
