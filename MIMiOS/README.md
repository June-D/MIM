# MIMiOS

iOS 客户端。目标：SwiftUI 壳 + Rust 协议核心（C ABI，不走 JNI）。

当前状态：**M0/M1 的库层已完成并可用**，UI 与真机运行等 Xcode。

## 目录

```
MIMiOSApp/                      SwiftUI 应用（XcodeGen：project.yml → .xcodeproj）
MIMKit/                         Swift Package（macOS 13 / iOS 16）
  Package.swift                 systemLibrary(MIMCoreABI) + library(MIMKit) + 冒烟程序
  Sources/MIMCoreABI/           mim_core.h 的副本 + module.modulemap
  Sources/MIMKit/
    MimCore.swift               C ABI 薄封装（密钥包、首条消息、签名/验签）
    Models.swift                与 src/messaging.rs 逐字对应的 DTO
    Keychain.swift              身份私钥 / 设备标识 / 状态密钥
    MIMClient.swift             登录、设备列表、注销（M2/M3 复用同一个 send）
  Sources/MIMKitSmoke/          不依赖 XCTest 的冒烟程序
  Tests/MIMKitTests/            XCTest（需要 Xcode 提供 XCTest）
  tools-sync-header.sh          从 MIMCoreRust/include 同步头文件
```

## 怎么跑 App（已验证到编译链接）

```bash
# 1) 只做一次：把命令行工具指向 Xcode，并接受许可（都需要你的密码）
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license accept

# 2) 起服务端（一条命令：MySQL + Redis + TLS 服务端）
./start.sh
# 2b) 生成 C ABI 的 xcframework（iOS 不能用 dylib）
MIMCoreRust/tools/build-ios.sh --check     # 先确认 iOS SDK 就位
MIMCoreRust/tools/build-ios.sh

# 3) 生成工程并构建（脚本会设好 MIM_USE_IOS_FRAMEWORK=1）
cd MIMiOS/MIMiOSApp && xcodegen generate
./build.sh                      # 设备架构（arm64），不签名 → BUILD SUCCEEDED
./build.sh simulator            # 模拟器（需要先下载模拟器运行时）

# 4) 与真服务端联调（不需要模拟器）
swift run MIMKitSmoke --login http://127.0.0.1:8080 composealice <密码>
swift run MIMKitSmoke --chat  http://127.0.0.1:8080   # 两个临时账号互发文本

# 5) 在 Xcode 里跑起来（选 Team 后可装真机）
open MIMiOSApp.xcodeproj
```

`project.yml` 是工程源，`.xcodeproj` 是生成物（已在 .gitignore 里）——pbxproj 是
机器生成的巨型 plist，提交进仓库只会带来无意义的合并冲突。

`Package.swift` 用环境变量切换两套链接方式：

- **macOS**：dylib（`swift run MIMKitSmoke` 不需要 Xcode，日常验证走这条）；
- **iOS**：`MIM_USE_IOS_FRAMEWORK=1` 时改用 `binaryTarget(MimCore.xcframework)`。

不用无条件 `binaryTarget` 的原因：SwiftPM 加载清单时就会校验路径，产物不存在会
**连 macOS 也编不了**，等于丢掉「没有 Xcode 也能验库层」这条性质。

两个已踩过的坑：

1. `binaryTarget` 路径必须在包内（`../` 会被拒绝），所以 xcframework 产出到包根目录，
   已 gitignore；
2. 静态库 xcframework 必须自带 `module.modulemap`（模块名 `MIMCoreABI`），
   否则 `import MIMCoreABI` 报 "Unable to resolve module dependency"。

### 还缺什么才能「跑起来」

- **模拟器**：`xcodebuild -downloadPlatform iOS`（约 7GB，`simctl list runtimes` 现在为空）；
- **真机**：Xcode 里选 Team（免费 Apple ID 也行，证书 7 天过期）；
- xcframework 目前只有 arm64 slice，Intel Mac 需补 `x86_64-apple-ios`。

## 怎么跑库（不需要 Xcode）

```bash
# 1) 先有 Rust 动态库（macOS 上验证用）
cd MIMCoreRust && cargo build --release

# 2) 冒烟：不需要 Xcode，也不需要 XCTest
cd MIMiOS/MIMKit && swift run MIMKitSmoke
#   · 生成 alice 密钥包
#   · 组装首条消息（tag 一致）
#   · 对端解出明文：iOS 第一条消息
#   · 证书归属还原为 alice-iphone
#   · 身份签名往返
#   · 篡改后验签失败
#   MIMKIT_SMOKE_OK

# 3) 单元测试（需要 Xcode 提供 XCTest）
swift test

# 4) 钥匙串用例默认跳过，需要时显式打开
MIM_KEYCHAIN_TESTS=1 swift test
```

## M2 已跑通（模拟器实测）

实时性与已读：WebSocket 长连接（`/websocket/compose`，25 秒心跳、断线 3 秒重连）
收到 100010/100012/100014 就叫醒 HTTP 增量；轮询保留 20 秒一次作兜底。
会话列表有未读红点，进入会话即标记已读（带 `clientMsgIds`，跨设备副本一起清），
对方读过后我发出的消息下方显示「已读」。

> 踩过的坑：已读回执的类型是 **100012** 而不是 100011——写错不报错，
> 只是「消息到了但一直显示未读」。`PushEventTests` 把编号钉住了。


```
会话列表                聊天页
composebob  19:39       干净环境下的第一条  19:38   ← 对方发来（左、灰）
这条由 iPhone 发出       这条由 iPhone 发出   19:39   ← 本机发出（右、蓝）
```

- 登录 → 发布密钥 → 会话列表 → 聊天页 → 发送 / 接收（3 秒轮询 `/message/undelivered`）；
- 本地库 `Library/Application Support/MIMLocal/messages.bin`：会话状态 + 会话索引 +
  聊天记录三样整体用 `mim_state_encrypt` 加密（AES-256-GCM，密钥 32 字节在 Keychain，
  每次加密换新 nonce）。文件头是 `MIM-STATE-1`，`grep` 不到任何明文——
  重启后历史仍在，且磁盘上看不到聊天内容；
- 收到消息后**必须** `POST /message/delivered?beforeId=`：服务端拉取不会自动置已投递，
  不确认的话同一密文每轮都会再下发，界面上表现为消息重复、时间戳不断刷新
  （我第一版就是这样，加了 ack 才干净）。

## 推送（极光 JPush）

服务端只是「叫醒」：推送正文固定为「你有新消息」，`extras` 带 `type/peer`，
客户端被唤醒后仍然走 `/message/undelivered` 拉取并解密——**服务端没有 from 列，
发送者被写在密文里，它连谁发的都不知道**。

链路：

```
消息落库 → 目标设备没有 WS 连接 → 服务端调 POST https://api.jpush.cn/v3/push
        → 极光 → APNs → 手机通知栏
App 启动 → JPUSHService 拿 registrationID → POST /device/push 存到该设备名下
点通知 → userInfo 里的 conversation_tag → 本地会话索引反查对端 → 直接打开会话
```

配置（都不入库）：

```bash
# ~/.mim/push.env
MIM_JPUSH_APP_KEY=...
MIM_JPUSH_MASTER_SECRET=...
MIM_JPUSH_APNS_PRODUCTION=false
```

iOS 侧：SwiftPM 引入 `jpush-sdk 6.3.0` + `jcore-sdk 5.6.0`，`AppKey` 从 Info.plist
的 `JPushAppKey` 读，**留空则整体跳过**（没配推送的环境照样能编译运行）。

> 点通知跳会话的关键取舍：服务端**不知道**是谁发的（没有 from 列），
> 所以推送里带的是 `conversation_tag`——双方共享的随机串，只有接收方能在本地
> 会话索引里反查出用户名。极光与服务端都看不出会话对象是谁。
>
> 两个坑：
> ① JPush 的 SPM 包**不导出 Swift 模块**，`import JPush` 会报
>    「Unable to resolve module dependency」，必须用桥接头
>    （`Sources/MIMiOS-Bridging-Header.h`）引入 `JPUSHService.h`；
> ② `JPUSHRegisterDelegate` 的方法在 Swift 里签名对不上（NSInteger →
>    UNAuthorizationOptions 的隐式转换），强类型声明会「不满足协议一致性」，
>    所以 delegate 传 nil——前台靠 WebSocket 实时收，推送只负责后台。
>
> **前置条件**：APNs 证书/密钥只能由**付费** Apple 开发者账号创建。
> 免费 Personal Team 无法开启 Push Notifications 能力，
> entitlements 已备好（`MIMiOSApp.push.entitlements`）但默认不启用。

## 找回邮箱与邮箱找回密码

- **绑定邮箱**：我 → 账号安全 → 找回邮箱（需要当前密码，服务端加密存储，只有本人看得到明文）；
- **忘记密码**：登录页右上角「忘记密码」→ 用户名 → 邮箱验证码（10 分钟有效，60 秒限发一次）
  → 新密码。重置成功后该账号所有设备下线。
- 服务端邮件通道用 `~/.mim/smtp.env`（阿里云邮件推送 DirectMail）：
  `MIM_SMTP_HOST=smtpdm.aliyun.com`、`MIM_SMTP_PORT=465`、`MIM_SMTP_USERNAME=<发信地址>`、
  `MIM_SMTP_PASSWORD=<SMTP 密码>`、`MIM_MAIL_FROM=<发件人>`。
  `./start.sh` 会自动读取，凭据不进仓库。

> 未配置邮件服务时，`/recover/email` 会明确返回「邮件服务未配置」，而不是假装发送成功。
> 账号没绑邮箱时对外仍返回成功（避免账号枚举），但服务端日志会写明原因。

## 好友备注与消息搜索

- **备注**：通讯录里长按好友 →「设置备注」（或左滑）。备注只在本机生效，
  会话列表、聊天头部、搜索结果统一显示「备注 > 昵称 > 用户名」；
- **消息搜索**：会话页顶部的搜索框，按关键字筛本机**已解密**的消息，点结果直接进会话。
  服务端只有密文，**搜索完全在本地完成**——能搜到什么取决于这台设备收到过什么，
  这是端到端加密的必然结果，不是缺陷。

## 功能对齐（与桌面端一一对应）

移动端与桌面端是同一套服务端接口，功能按「标签页」重组：

| 标签页 | 功能 |
|---|---|
| 会话 | 会话列表（头像/预览/未读角标/时间）、下拉刷新、长按清除会话、离线提示 |
| 通讯录 | 好友列表（在线状态/备注名/删除）、好友申请（同意/拒绝/已发出）、搜索添加好友 |
| 我 | 身份指纹、设备列表（左滑注销）、账号安全（二次验证/恢复码/修改密码）、清除本地记录、重置身份、退出登录 |
| 聊天 | 文字/图片/文件/语音、已读标记、撤回、清除会话、长按复制、图片全屏、文件导出 |

**只有好友之间才能对话**——和桌面端一致：添加好友需对方同意。

> 接口差异：`GET /chat/contacts` 直接返回数组，不套 `{status,data}` 信封，
> 用通用 `send` 会报「响应不是预期的 JSON」；这条单独走原始响应解析。

## 多设备历史同步（两台设备实测）

新设备登录时若本地库为空，会 `POST /message/sync/request`；同一账号的其它设备收到
100022 后把**最近 30 天 / 50 条**加密转发过去：

```
源设备 e9b7f04b ──100022──▶ 收到请求，逐条封成 SyncPayload
   ↓ 用身份私钥对 clientMsgId|tag|from|to|device 签名
   ↓ 用与目标设备之间的会话加密（没有会话就先走 X3DH 首条消息）
POST /message/sync ──▶ 服务端按 client_msg_id + 标签找原消息、校验后复制成新副本
   ↓
新设备 7845a5c2 ──/message/undelivered──▶ 解密 + 验签 → 入库（带「来自另一台设备」）
```

实测（两个模拟器，同一账号）：

```
id  1836  target=7845a5c2  sync_from=e9b7f04b  client_time=11:24  server_time=11:27
id  1837  target=7845a5c2  sync_from=e9b7f04b  client_time=11:24  server_time=11:27
```

新设备列表里直接出现「composebob / 同步验证二 / 11:24」，未读 2 条——时间是原始时间，
不是同步时间。

> 踩过的坑：**同步副本必须带原消息自己的会话标签**。服务端按
> `client_msg_id + conversation_tag` 找原消息，而一条逻辑消息在不同接收设备上的标签
> 各不相同（实测同一条消息三台设备的标签是 `egIVwWq9kX` / `c7BKfdCZCp` / `TiFaHoO3aS`）。
> 用错标签就得到 `40001 没有可同步的原消息`。另外「首条消息」分支里我原来写的是
> `break`，导致只导出一条就结束——必须是 `continue`。

## 功能收尾（整机验证过的清单）

| 项 | 行为 |
|---|---|
| 会话列表 | 头像 / 预览（媒体显示 `[图片]/[语音]/[文件] 名称`）/ 时间 / 未读角标；下拉刷新；长按可清除会话 |
| 离线提示 | 长连接断开时列表顶部橙色「连接已断开，正在重连…」，连上即消失 |
| 聊天页 | 左右气泡、日期分隔、已读标记；图片点击全屏（可缩放 + 系统分享）；文件点击解密导出（系统分享面板）；语音可播放，播完按钮复位 |
| 我的设备 | 本机指纹、设备列表（左滑注销其它设备）、清除本地聊天记录、退出登录 |
| 应用角标 | 未读总数同步到图标（`setBadgeCount`） |
| 登录态 | Keychain 持久化，冷启动静默恢复；失效才回登录页 |

> 媒体消息的正文是空的：**列表预览必须自己给占位**，否则会话列表第二行是空白，
> 看起来像坏了（第一版就是这样）。

## 真机上的体验细节

- **登录态持久化**：令牌、账号、服务端地址存 Keychain，冷启动先拿 `GET /device`
  探一次；通过就直接进会话列表，失效则静默回登录页。重开 App 不用重新登录。
- **头像**：会话列表与聊天头部显示圆形头像；服务端没头像时回落到首字母 + 按用户名
  哈希取色的底色（同一个人永远同色）。头像是公开资料，走普通 HTTP，不做 E2EE。
- **时间**：列表里今天显示 `HH:mm`、昨天显示「昨天」、更早显示日期；聊天页按天插入
  日期分隔。
- **错误提示可关闭**：发送/撤回失败时聊天页底部出现红色横幅，右侧有 ×。

## 撤回与会话清除

- **撤回**：长按自己发出的气泡 →「撤回」。服务端按 `clientMsgId` 撤掉所有设备副本、
  抹掉密文，并给对端推 100014；本机立刻把气泡改成「已撤回」。
- **清除会话**：聊天页右上角「…」→「清除会话」。只做**单方**清除——双向清除需要
  按会话标签抹服务端密文，而 iOS 不持有标签。
- 收到 100014 时按 `clientMsgId` **更新原消息**，不是追加一条「已撤回」的新消息。

> 踩过的坑：撤回接口**强制要求 `tags`**，不带会得到
> `40001 缺少会话标签`。iOS 的标签在会话索引里（`对端|设备 → SessionRef.tag`），
> 撤回时取该对端的全部标签一起传。另一个坑更隐蔽：聊天页原来不显示错误条，
> 失败时用户看到的就是「点了没反应」——已补上红色错误条。

## M3 附件：图片 / 文件 / 语音都已跑通

输入区从左到右：相册、文件、语音。

- **图片**：`PhotosPicker` 选图 → 加密 → 上传 → 气泡显示；
- **文件**：`.fileImporter` 选任意文件（记得 `startAccessingSecurityScopedResource`，
  否则沙盒外读出来是空）→ 气泡显示文件名与大小；
- **语音**：`AVAudioRecorder` 录 m4a（首次申请麦克风权限，`NSMicrophoneUsageDescription`
  必须写在 Info.plist，否则系统直接拒绝且不弹窗）→ 气泡带时长与播放键；
  播放时解密到临时文件再交给 `AVAudioPlayer`，播完即删、按钮复位。

接收侧三种气泡都能显示（截图里：对方发来的「月度报表.xlsx 48 KB」与 1 秒语音，
以及自己发出的「季度预算.xlsx 24 KB」）。

> **权限文案必须写在 `project.yml` 的 `info.properties` 里**，不要直接改
> `Sources/Info.plist`：XcodeGen 每次 `generate` 都会用 properties 覆盖那个文件。
> 缺 `NSMicrophoneUsageDescription` 时，一点麦克风按钮系统就**直接杀掉进程**
> （表现为「点一下秒退」，日志里是 TCC 的 privacy-sensitive data 崩溃）。
>
> 已知未自动化项：**录音**需要麦克风权限弹窗，无法脚本化验证；
> 播放链路（解密 → AVAudioPlayer）已实测，播放结束后按钮会复位。

## M3 图片已跑通

右下角是 App 内生成并发出（`sendImage`：加密 → `POST /media` → 消息里带引用），
左侧是从命令行发来、App 解密后显示的图片。

协议与桌面端完全一致：

- 明文用 32 字节一次性密钥 + 12 字节 nonce 走 `mim_state_encrypt`（AES-256-GCM，
  `MIM-STATE-1` 三行文本），密文上传 `POST /media`（>8MB 自动分片）；
- 消息正文是 `{"v":1,"kind":"image","text":"","media":{id,key,size,mime,name,…}}`，
  `key` 是 Base64 的 32 字节密钥——服务端既解不开也看不到文件名；
- 本地只缓存**密文**（`MIMLocal/media/<id>`），解密结果只在内存里。

> 踩过的坑：判断「这条消息是不是媒体载荷」不能按 `{"v":1,"kind":` 前缀匹配——
> JSON 键顺序不保证（Swift 的 JSONEncoder 会把 text 排到最前），要看解码结果。
> 老数据里已经存成文本的载荷会在登录时自动升级成附件消息。

## 模拟器实测（2026-11-23）

iPhone 17 / iOS 27.2 模拟器上跑通：登录 → 发布密钥 → 设备列表。

```
我的设备
  设备标识  20ca6077-5da0-4857-b05d-35822e6a00ae
  身份指纹  D928 526F 3219 A038
  设备（10）MiM Test 本机 / desktop-8d6c009a / …
```

过程中踩到两个只在 iOS 上才出现的问题：

1. **模拟器不能用 `CODE_SIGNING_ALLOWED=NO`**：没有 CodeSign 时 Keychain 返回
   `errSecMissingEntitlement(-34018)`，症状是「登录成功但身份指纹一直空、设备列表 0 台」。
   模拟器保持默认的 ad-hoc 签名即可；真机才需要开发者 Team。
2. **登录不要放在 `.task {}` 里**：登录一成功界面就切到设备页，`LoginView` 消失时
   SwiftUI 会取消 `.task` 正在进行的异步工作，表现为发布密钥的请求被取消
   （`transport("cancelled")`）。用非结构化 `Task { await model.login() }`。

## 两个环境坑（都遇到过，写下来省时间）

1. **只装了 Command Line Tools 时**，`swift build` 可能报
   `You have not agreed to the Xcode license agreements`。临时绕过：

   ```bash
   DEVELOPER_DIR=/Library/Developer/CommandLineTools swift run MIMKitSmoke
   ```

   装了 Xcode 后先 `sudo xcodebuild -license accept` 再
   `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`。

2. **`swift test` 需要 Xcode**：XCTest 不随 Command Line Tools 提供，会报
   `no such module 'XCTest'`。所以库层验证用 `MIMKitSmoke`（不依赖 XCTest），
   单测等 Xcode 到位再跑。

## 与桌面端的关系

- 密码学一行都不重写：全部经 `MIMCoreABI` 调 Rust（22 个导出，见
  [`docs/ios-api-map.md`](../docs/ios-api-map.md)）；
- 协议字段与 `MIMCoreRust/src/messaging.rs` 逐字对应，`ContractTests` 钉住关键字段名；
- 服务端接口完全复用，M1~M3 不需要改服务端。

## 下一步

1. 装好 Xcode → `MIMCoreRust/tools/build-ios.sh` 产出 `MimCore.xcframework`，
   把 `Package.swift` 里的 dylib 换成 `.binaryTarget`；
2. 新建 `MIMiOSApp`（SwiftUI）：登录页 → 会话列表 → 聊天页；
3. Keychain 存身份私钥，`MimCore` 生成密钥包并上传 `/key/bundle`；
4. 之后按 `docs/ios-plan.md` 的 M2/M3 推进（WebSocket、媒体分片）。
