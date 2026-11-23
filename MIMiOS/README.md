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

# 4) 在 Xcode 里跑起来（选 Team 后可装真机）
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
