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
    data/MessagingBackend.kt  传输层契约（登录/联系人/历史/发送/轮询）
    data/DemoBackend.kt   内存演示实现（含回声，仅用于跑通 UI）
    data/SecureBackend.kt 真实收发：设备密钥 + 首条消息 + sealed 收发 + 本地缓存
    data/LocalVault.kt    本地保险库（密钥包/会话/消息，全部加密落盘）
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

| 能力 | 演示后端 | 真实后端（SecureBackend） |
|---|---|---|
| 登录 | 模拟 | `POST /login/token`，令牌绑定设备 |
| 设备与密钥 | 无 | Rust 生成，登记 + 上传（缺失时自动补） |
| 联系人 | 固定两条 | `GET /chat/contacts`（分组展开） |
| 发送 | 内存回声 | 首条消息 / sealed 消息，Rust 组装后投递 |
| 接收 | 无 | 轮询 `/undelivered`，Rust 解密 + 落缓存 |
| 历史 | 内存 | 本地**加密缓存**（换设备或清库后不保留） |
| 撤回 | 无 | 发送时生成凭证；`POST /message/recall`，本地立即置「已撤回」 |
| 已读回执 | 无 | 打开会话 `POST /message/read`；自己消息的已读靠历史轮询 |
| 离线队列 | 无 | 投递失败入队，只重发同一份密文，不重复推进棘轮 |

协议计算全部在 Rust 核心；Kotlin 只做传输、入库与 UI 语义。

### 状态刷新（没有推送时）

`SecureBackend.historyStates` 每轮轮询对**当前打开的会话**查一次历史：
更新自己消息的已读状态与双方的撤回状态。其它会话要等下次打开才刷新。
接入 WebSocket 推送后可去掉这次轮询。

### 本地保险库

默认在 `~/.mim/<用户名>/<设备>/`（可用 `-Dmim.vault.dir` 覆盖）：

- `key.bin` —— 32 字节保险库密钥（0600）
- `bundle.bin` —— 身份密钥 / 预密钥（加密）
- `sessions.bin` —— 会话存储（加密）
- `messages.bin` —— 消息缓存（加密）
- `outbox.bin` —— 离线发送队列（加密，密文已组装，重试只重发）
- 同级的 `device.txt` 保存稳定设备标识

桌面端只能做到文件权限保护；Android/iOS 应改用 Keystore / Keychain。
清空该目录等于丢弃本设备的私钥与会话，旧消息将无法解密。

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
