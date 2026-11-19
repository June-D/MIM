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

### 设备与密钥（KeyRegistry）

`KeyRegistry.provision` 走完整契约：登记设备 → 上传身份公钥 → 上传签名预密钥
→ 批量上传一次性预密钥。密钥材料由 Rust 核心生成（JNI），私钥不出进程。

运行联调测试前需要先构建带 JNI 的动态库：

```bash
cd ../MIMCoreRust && cargo build --release --features jni
cd ../MIMCompose
MIM_SERVER_URL=http://127.0.0.1:8080 \
MIM_NATIVE_LIB=../MIMCoreRust/target/release/libmim_core_rust.dylib \
  ./gradlew :composeApp:desktopTest
```

注意：`cargo build --release` 不带 `--features jni` 会覆盖动态库并去掉 JNI 符号。

服务端校验的是**明文口令**（BCrypt）；上传 Base64 会一直失败。

### 首条消息联调

`FirstMessageTest` 打通「A 组装 → 服务端 → B 拆解」：

- A 用 Rust 生成 X3DH 首条消息（证书信封 + 会话标签 + 双棘轮密文）
- 服务端按 sealed + tag 契约落库
- B 拉 `/message/undelivered`，用 Rust 重建会话并解出明文与证书

同一用例还会把双方会话写入 Rust 的会话存储，再走一轮 sealed 回信
（JNI 往返），验证棘轮推进与标签收敛。

同样需要 `MIM_SERVER_URL` 与 `MIM_NATIVE_LIB`。测试使用固定账号
（首次创建，后续复用），避免撞注册限流。连续反复跑会撞登录限流
（每 IP 每分钟 10 次），等一分钟即可。

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
