# MIMCompose

PC 桌面客户端（Compose Multiplatform）。

## 定位

当前先做透桌面端，再把同一套状态与职责划分复用到手机端。

- 已有：注册 / 登录 / 找回密码 → 联系人 → 聊天 → 设备；真实端到端收发、撤回、
  已读、离线队列、WebSocket 推送、未读数、清除会话、设备管理、安全码核对、
  添加联系人、好友备注、屏蔽、移除好友、记住账号密码、用户协议
- 不在这里：协议与密码学。基准实现在 `MIMCore`，实际执行在 `MIMCoreRust`
  （C ABI / JNI）。本模块只做传输、入库与 UI 语义

这样分层的目的：界面只表达业务语义，密码学独立演进，两端互不阻塞。

## 目录

```
MIMCompose/
  run-desktop.sh          启动脚本：构建 JNI 动态库、检查服务端、启动界面
  composeApp/src/desktopMain/kotlin/com/junesoon/mim/app/
    Main.kt               窗口入口（920×660）
    MimApp.kt             路由分发，只读状态
    domain/Model.kt       Contact / ChatMessage
    domain/DeviceInfo.kt  设备、对端身份公钥、安全码条目
    domain/SafetyNumber.kt 60 位安全码（与 Java 同算法）
    data/MessagingBackend.kt  传输层契约（登录/收发/推送/设备/清除）
    data/DemoBackend.kt   内存演示实现（含回声，仅用于跑通 UI）
    data/HttpBackend.kt   登录与令牌
    data/KeyRegistry.kt   设备登记、密钥上传、旧设备清理
    data/SecureBackend.kt 真实收发：首条消息 / sealed / 撤回 / 已读 / 离线队列 / 推送
    data/LocalVault.kt    本地保险库（密钥包/会话/消息/队列，全部加密落盘）
    data/messaging/Protocol.kt  服务端 DTO 与 Rust 边界 DTO
    data/Codec.kt         十六进制 / Base64 转换
    data/JsonCodec.kt     统一 JSON 配置
    state/AppStore.kt     唯一状态源：StateFlow + 动作 + 同步循环
    ui/Theme.kt           配色、字体、圆角（视觉系统唯一来源）
    ui/Icons.kt           本地 Canvas 图标，避免额外依赖
    ui/Avatar.kt          首字母头像，颜色由名字稳定推导
    ui/AuthScreen.kt      登录 / 注册 / 找回密码 / 用户协议
    ui/ContactsScreen.kt  联系人（含未读角标、添加与设备入口，空态提示）
    ui/AddContactScreen.kt 搜索用户、好友申请、同意或拒绝
    ui/ChatScreen.kt      聊天（撤回、安全码、清除）
    ui/DeviceScreen.kt    设备列表与注销
    data/CredentialStore.kt 记住账号密码（AES-GCM + 0600）
    resources/terms/2026-11.txt 用户协议原文（运行时读取）
  composeApp/src/desktopTest/kotlin/  状态流测试 + 真实后端联调
```

## 运行

```bash
# 推荐：一条命令检查环境并启动（自动构建带 JNI 的 Rust 动态库）
MIMCompose/run-desktop.sh
MIMCompose/run-desktop.sh --check           # 只检查，不启动界面

# 等价的手动方式
cd MIMCompose
./gradlew :composeApp:run          # 演示后端（内存）
./gradlew :composeApp:desktopTest  # 状态流与联调测试

MIM_SERVER_URL=http://127.0.0.1:8080 \
MIM_NATIVE_LIB=../MIMCoreRust/target/release/libmim_core_rust.dylib \
  ./gradlew :composeApp:run
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
| 实时推送 | 无 | WebSocket `/websocket/*`，收到 100010/100012/100014 即触发同步 |
| 设备管理 | 无 | `GET /device` 列表 + `DELETE /device/{id}` 注销 |
| 安全码 | 无 | 60 位安全码（与 Java 同算法），按对端设备逐个展示 |
| 未读数 | 内存计数 | `GET /message/unread/{peer}`，联系人页角标以服务端为准 |
| 清除会话 | 无 | `POST /message/clear`，可只清自己的或双方清除；本地缓存一并丢弃 |
| 添加联系人 | 无 | `GET /chat/search` 搜索 → `POST /chat/requests` 申请 → 对方同意 |

协议计算全部在 Rust 核心；Kotlin 只做传输、入库与 UI 语义。

### 实时推送与兜底

`SecureBackend.listenPush` 连 `ws(s)://host/websocket/compose`，握手带
`Authorization` 与 `X-Device-Id`；只把「有新数据」通知给上层，具体拉取仍走 HTTP。
收到新消息 / 已读回执 / 撤回事件即时触发一次同步；
收到对方「清除会话」通知（100019）时直接丢弃本地缓存，避免显示已被抹掉的消息。

同时保留 3 秒轮询兜底：推送断开或丢失时最多延迟 3 秒；重连间隔 3 秒，
25 秒无帧就发文本心跳。

### 状态刷新

`historyStates` 每轮对**当前打开的会话**查一次历史，更新已读与撤回状态；
其它会话在下次打开时刷新（推送只会触发同步，不会直接改本地状态）。

### 添加联系人

联系人页「添加」进入添加页：输入用户名搜索 → 发送申请；对方的申请在
「待我处理」里同意或拒绝。申请必须对方同意才建立关系，不存在静默加好友。
收到申请 / 同意 / 拒绝的推送（100016~100018）会即时刷新申请列表；
对方同意后联系人列表立刻出现对方，不需要重新登录。

### 设备与安全码

联系人页「设备」进入设备管理：可查看本账号设备并注销其它设备
（注销后该设备令牌立即失效）。聊天页「安全码」按对端每台设备展示
60 位安全码，双方排序后哈希，因此两端算出同一个码，可线下核对。

注销是软删除，服务端会拒绝该设备的任何请求。本地已注销设备的登录会给出
明确提示（删除 `~/.mim/<用户名>` 后重新登录，或换设备）；旧设备目录保留，
离线仍可查看历史。若只是服务端缺少设备行（库被清理），登录时会用**同一份**
密钥重新登记，已有会话与棘轮不受影响。

### 账号入口

登录页同时承载三种形态：登录、注册、找回密码。

- **注册**：用户名 / 密码 / 可选邮箱 / 设备名；必须勾选并查看《用户协议》
  （版本 `2026-11`，原文在 `resources/terms/2026-11.txt`，运行时读取）。
  注册成功后立即用同一账号登录。
- **记住账号密码**：勾选后把账号、密码、设备名写入 `~/.mim/remember.bin`，
  用同目录 `remember.key`（0600）做 AES-GCM 加密；下次启动自动登录。
  这只防「随手翻到明文」，不防能读你用户目录的恶意程序，界面已写明。
- **找回密码**：`POST /recover/email` 发验证码、`POST /recover/reset` 重置。
  需要账号绑定邮箱且服务端配置 SMTP；未配置时服务端返回
  「邮件服务未配置」，界面原样提示，不假装成功。

### 联系人操作

聊天页右上「更多」提供：设置备注（`POST /chat/contacts/{peer}/label`）、
屏蔽此人 / 解除屏蔽、清除会话、移除联系人（`DELETE /chat/contacts/{peer}`，双向）。

- **屏蔽是本地行为**：消息仍会解密以推进棘轮，但明文直接丢弃、不提示；
  联系人标记「已屏蔽」，未读数固定为 0，输入区替换为说明条。对方不会收到通知。
- **移除好友是双向的**：服务端同时解除双方关系，本机历史仍保留在保险库里。
- **备注**只影响自己看到的显示名，清空备注即恢复对方昵称。

### 未读数与清除会话

联系人列表的未读角标取自 `GET /message/unread/{peer}`，避免只靠本地计数漂移；
每个联系人一次请求，因此节流到 5 秒一次，进入联系人页时强制刷新。

聊天页「清除」提供两种语义：

- **只清我的**：服务端记录清除点，仅影响自己；对方记录不受影响。
- **双方清除**：服务端抹掉这段密文，对方也看不到。

两种都会同时丢弃本地缓存（`messages.bin` 中该联系人的记录），
否则下次打开会看到服务端已过滤、本地还留着的旧消息。清除后仍可继续收发。

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
