# iOS 立项稿：先做能跑的最小闭环

> 结论先说：**本机现在还不能构建 iOS 应用**——只装了 Command Line Tools，没有 Xcode。
> 这不是推测，是实测：Rust 核心交叉编译到模拟器时，在链接阶段被 macOS SDK 拦住了
> （详见第 1 节）。装好 Xcode 之后，这份文档里的 M0 可以当天验完。

## 1. 环境实测（2026-11-23）

| 检查 | 结果 |
|---|---|
| `xcodebuild -version` | ❌ `requires Xcode, but active developer directory ... CommandLineTools` |
| iOS 模拟器 | ❌ `simctl list devices available` 无输出（没有 iOS runtime） |
| Rust iOS target | ✅ `aarch64-apple-ios` / `aarch64-apple-ios-sim` 已装 |
| Rust 交叉编译 | ❌ 链接失败：`building for 'iOS-simulator', but linking in dylib (…MacOSX26.5.sdk…/libiconv.2.tbd) built for 'macOS'` |
| 协议核心的 C ABI | ✅ `MIMCoreRust/include/mim_core.h` 已有 15 个导出（会话、加解密、密钥包…），**不需要 JNI** |
| 工程里的 iOS target | ❌ 没有（`composeApp` 目前只有 desktop） |

最后一条是决定性的：**iOS 侧最大的未知不是密码学，而是工具链**。协议核心已经能
通过 C ABI 被任何语言调用，这一点在写 Rust 的时候就留好了。

## 2. 架构选择：SwiftUI + Rust 静态库（推荐），不是 Compose iOS

| 方案 | 优点 | 代价 |
|---|---|---|
| **A. SwiftUI + Rust C ABI**（推荐） | 原生输入法/滚动/键盘避让/手势；包体小；Keychain、APNs、后台唤醒都是原生能力 | UI 要重写一遍（登录、列表、聊天、媒体四个界面） |
| B. Compose Multiplatform iOS | 复用现有 Compose 界面代码 | 需要 Kotlin/Native + skiko；包体与启动开销明显；输入法/滚动/键盘行为与系统不一致，聊天类应用最容易在这里被用户感知 |

聊天应用的体验高度依赖「输入框、键盘、滚动、长按复制、图片选择」这些系统原生行为，
所以选 A。**协议与密码学一行都不用重写**：Rust 编译成 `.a` / `.xcframework`，
Swift 通过 `mim_core.h` 调用；密钥只经过内存，落盘交给 Keychain。

## 3. 里程碑

**M0 环境与骨架 —— 已完成（2026-11-23）**

实际结果：
- Xcode 27.2 + iOS 27.2 SDK 就位；`xcodebuild -runFirstLaunch` 装好了系统组件
  （缺它时 xcodebuild 会报 `IDESimulatorFoundation` 插件加载失败）；
- `MIMCoreRust/tools/build-ios.sh` 产出 `MIMiOS/MIMKit/MimCore.xcframework`
  （设备 arm64 + 模拟器 arm64）；静态库必须自带 modulemap，否则 SwiftPM 报
  "Unable to resolve module dependency"；
- `MIMiOS/MIMiOSApp/build.sh` 编译链接成功：`** BUILD SUCCEEDED **`
  （`Debug-iphoneos/MIMiOSApp.app`，arm64，未签名）；
- 尚未「跑起来」：**没有 iOS 模拟器运行时**（`simctl list runtimes` 为空），
  需要 `xcodebuild -downloadPlatform iOS`（约 7GB）；或接真机并选签名 Team。

原始计划（供对照）：

> Swift 侧要调的接口已盘点完毕，见 [`ios-api-map.md`](ios-api-map.md)；
> C ABI 现有 22 个导出（含首条消息与身份签名），`cargo test --test ffi_abi_test` 可验。

- 装 Xcode（App Store）+ 命令行工具指向它：`sudo xcode-select -s /Applications/Xcode.app`
- `MIMCoreRust/tools/build-ios.sh` 产出 `MimCore.xcframework`（device + simulator 双架构）
- 新建 SwiftUI 工程 `MIMiOS`，链接 xcframework；
- ABI 已提前验证过：`tools/swift_abi_smoke.sh` 在 macOS 上用 Swift 跑通了
  密钥包生成 → 组装首条消息 → 对端解出明文 → 签名 + 篡改被拒（见 `ios-api-map.md`），
  所以 M0 剩下的只是「把它装进 App 并在模拟器里跑起来」

**M1 登录与本地保险库 —— 已在模拟器跑通（2026-11-23）**

iPhone 17 / iOS 27.2 上验证：登录 → 发布密钥（设备登记 + 身份公钥 + 签名预密钥 +
一次性预密钥）→ 设备列表 → 密钥包存 Keychain（显示身份指纹）。
两个 iOS 特有坑记在 `MIMiOS/README.md`：模拟器关签名会让 Keychain 报 -34018；
登录放在 `.task` 里会被视图切换取消。

> 库层已提前完成：`MIMiOS/MIMKit` 里 `MimCore`（ABI 封装）、`Keychain`、
> `MIMClient`（登录/设备）都已可编译，`swift run MIMKitSmoke` 通过。
> M1 剩下的只是 SwiftUI 界面与流程串联。

- 登录（账号密码 + 设备名 + 二次验证码）、令牌存 Keychain
- 身份密钥包生成后存 Keychain（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`），
  消息与会话缓存用 CryptoKit AES-GCM 落沙盒，密钥同样在 Keychain
- 连续 3 次密码错误自毁：删沙盒目录 + Keychain 条目（与桌面同一套语义）

**M2 文本收发 —— 已完成（模拟器实测，2026-11-23）**

登录 → 发布密钥 → 会话列表 → 聊天页 → 收发；本地库整体加密落盘
（`MIM-STATE-1` 格式，磁盘上无明文）。实时性用 WebSocket 长连接（25 秒心跳、断线 3 秒重连），收到推送即拉增量；
轮询保留 20 秒一次作为兜底。已读：进入会话标记已读（带 `clientMsgIds`），
对方读过后显示「已读」；**已读回执类型是 100012**。

原始计划（供对照）：
- URLSession + WebSocket（`wss://`）连 `/websocket/...`，与桌面同一套推送协议
- 会话列表、聊天页、离线队列、已读回执、撤回
- 验收：与桌面端互发文本，双向一致；断网重连不丢消息

**M3 媒体 —— 图片已完成（模拟器实测）**

相册选图（`PhotosPicker`）→ 加密 → `POST /media`（>8MB 分片）→ 消息带引用；
接收端按 id 下载密文、用载荷里的密钥解密并显示。本地只缓存密文。
**文件与语音也已接**：文件走 `.fileImporter`（注意 security-scoped 访问），
语音用 `AVAudioRecorder` 录 m4a、`AVAudioPlayer` 播放（解密到临时文件，播完即删）。
语音的**录制**依赖麦克风权限弹窗，无法脚本化验证；播放已实测。

原始计划：
- 相册/相机选图、录音、文件（iCloud Drive）
- 复用现有媒体链路：一次性密钥 AES-GCM → `/media` 上传（>8MB 走分片）
- 进度与失败重试；本地密文缓存

**M4 推送与多设备（2 天，可选）**
- 前台已够用；后台推送需要 Apple Developer 账号生成 APNs `.p8`，
  服务端新增 APNs 通道（现在只有 WebSocket）。这是**唯一需要额外服务端工作**的部分
- 多设备历史同步确认框（复用桌面已有的信任模型与指纹比对）

**M5 打磨（持续）**
- 深色模式、动态字体、VoiceOver 标签、iPad 分栏

## 4. 服务端要不要改

**M1–M3 完全不用改**：所有接口都是 HTTP + JSON + 二进制，没有桌面专属逻辑。
只有 M4 的 APNs 需要新增一个推送通道（以及一份 Apple 签发的密钥）。

## 5. 需要你提供 / 决定的

1. **装 Xcode**（App Store，约 10GB+，需要 Apple ID）。
2. 用哪种签名跑真机：
   - **免费个人签名**：可装机，但证书 7 天过期，需重新签；
   - **开发者账号**（$99/年）：有效期一年，才能用 APNs。
3. iOS 最低版本：建议 **iOS 16**（SwiftUI 的成熟度与覆盖率平衡点）。
4. 是否接受「**先不做后台推送**」的 MVP（前台 WSS 已经能收到消息）。

## 6. 验收标准（M0）

- `tools/build-ios.sh` 能产出 `MimCore.xcframework`（arm64 device + arm64/x86_64 sim）；
- SwiftUI 工程在模拟器启动，界面上打印一次密钥包生成结果（长度与版本号）；
- `xcodebuild -scheme MIMiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
  成功。

## 7. 已知风险

- **没有 Xcode 之前一切停在纸面**：本文档第 1 节的实测就是证明，不要先写 UI 再发现构建不通。
- **Rust → iOS 的链接细节**：iOS 不允许 dylib 分发，必须静态库 + `-lc++` 等系统库；
  脚本里已经写成 staticlib。
- **Keychain 与自毁的边界**：iOS 上删掉 App 时 Keychain 项默认保留，
  所以「重装后仍能读到旧密钥」是需要显式处理的行为（第一启动时清理孤儿项）。
- 真机调试需要信任开发者证书，属于系统层操作，无法自动化。
