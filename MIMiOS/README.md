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

# 2) 生成 C ABI 的 xcframework（iOS 不能用 dylib）
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
