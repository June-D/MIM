# 客户端

两端共用同一套服务端接口与加密格式，消息可以互通。

## 桌面端（MIMCompose）

```bash
./MIMCompose/run-desktop.sh https://<host>:8443
```

自签证书需要让 JVM 信任它：

```bash
TS=~/MIM-data/tls/mim-truststore.p12
keytool -exportcert -alias mim -keystore ~/MIM-data/tls/mim-tls.p12 \
  -storepass changeit -file /tmp/mim-ca.crt
keytool -importcert -noprompt -alias mim-ca -file /tmp/mim-ca.crt \
  -keystore "$TS" -storetype PKCS12 -storepass changeit

JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$TS -Djavax.net.ssl.trustStorePassword=changeit" \
  ./MIMCompose/run-desktop.sh https://127.0.0.1:8443
```

本地数据在 `~/.mim/`（账号、密钥包、消息，均加密落盘）；`remember.bin` 记的是自动登录凭据，
换成别的账号测试时删掉它即可。

## iOS（MIMiOS + MIMKit）

```bash
# 1) 密码学核心 → xcframework（iOS 不能用 dylib）
cd MIMCoreRust && ./build-ios.sh

# 2) 生成工程（工程文件由 project.yml 生成，不进仓库）
cd MIMiOS/MIMiOSApp && xcodegen generate

# 3) 编译并装到真机
xcodebuild -project MIMiOSApp.xcodeproj -scheme MIMiOSApp \
  -destination 'id=<UDID>' -derivedDataPath /tmp/mim-device-build \
  DEVELOPMENT_TEAM=<TeamID> -allowProvisioningUpdates build
xcrun devicectl device install app --device <UDID> \
  /tmp/mim-device-build/Build/Products/Debug-iphoneos/MIMiOSApp.app
```

首次需要在手机上「设置 → 通用 → VPN 与设备管理」里信任开发者证书。
免费账号的 provisioning profile **7 天过期**，到期重跑上面两条命令。

### 两个必须知道的约定

1. **plist 属性以 `project.yml` 的 `info.properties` 为准**，不要直接改 `Sources/Info.plist`
   （XcodeGen 每次 generate 都会覆盖）。缺 `NSMicrophoneUsageDescription` 这类 key 时，
   一点按钮系统就**直接杀掉进程**，表现为「点一下秒退」。
2. **自签证书靠指纹锁定，内置在包里**（`Resources/mim-local-ca.cer`，只有公钥）。
   换证书/换 IP 必须重新打包，不能在手机上装描述文件绕过。

### 依赖

| 依赖 | 用途 |
|---|---|
| `MIMKit`（本地 SPM 包） | 网络、加密会话、本地库、WSS |
| `jpush-sdk 6.3.0` + `jcore-sdk 5.6.0` | 推送（SPM 包**不导出 Swift 模块**，靠桥接头引入 `JPUSHService.h`） |

## 功能对照

| 能力 | 桌面 | iOS |
|---|---|---|
| 文字 / 图片 / 文件 / 语音 | ✅ | ✅ |
| 实时（WSS）、未读、已读 | ✅ | ✅ |
| 撤回、单方清除会话 | ✅ | ✅ |
| 好友：申请/同意/删除/备注/搜索 | ✅ | ✅ |
| 多设备历史同步 | ✅ | ✅ |
| 二次验证 / 邮箱找回 / 修改密码 | ✅ | ✅ |
| 群聊 | ⬜ | ⬜ |

## iOS 的已知边界

- **搜索只在本机**：服务端没有明文，搜不到本机没收到过的消息；
- **后台推送**需要 APNs：证书只能由付费开发者账号签发。代码与 entitlements 已备好，
  拿到账号后打开 `CODE_SIGN_ENTITLEMENTS` 即可；
- **点通知跳会话**依赖推送里的 `conversation_tag`（随机串，不含身份）与本地会话索引，
  索引过旧时会退回到列表页。
