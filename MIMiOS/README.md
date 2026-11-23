# MIMiOS

iOS 客户端。目标：SwiftUI 壳 + Rust 协议核心（C ABI，不走 JNI）。

当前状态：**M0/M1 的库层已完成并可用**，UI 与真机运行等 Xcode。

## 目录

```
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

## 怎么跑

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
