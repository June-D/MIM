# MIM · 端到端加密私密通讯（项目宪法）

Spring Boot 服务端 + Compose Multiplatform 桌面客户端（`MIMCompose`，唯一 UI）。
**完整共识见根目录 `README.md` 的《产品基调（共识）》，动手前必须先读。**
本文件只做 L1 摘要与导航，冲突时以 README 那一节为准。

## 定位

注重安全与隐私的**个人一对一**端到端加密通讯产品。不做群聊、不做内容推荐、
不做云端明文备份。服务端不持有私钥，只见密文与必要元数据。

## 模块

```
MIMServer/     Spring Boot 3.5 / Java 17：鉴权、设备登记、预密钥分发、密文转发、
               发送者证书、已读/撤回/清除状态、限流
MIMCoreRust/   Rust 安全内核：X3DH、双棘轮、HKDF、AES-GCM、信封、会话存储、
               证书验签、状态加密；出口 JNI 与 C ABI
MIMCore/       Java 协议核心：行为基准与测试向量（不在生产路径）
MIMCompose/    Compose 桌面客户端（JVM 17）：传输、缓存、UI 语义
```

## 硬约束

1. 密码学只在 `MIMCoreRust`；Kotlin/Swift/Java 业务层不写加密、不碰明文私钥。
2. 改协议必须同步：向量 → Rust → 两端客户端 → 文档；`cargo test` 与
   `tools/interop.sh` 对拍通过。
3. 未接入的能力显式报错或返回空；**禁止造假数据、禁止假装成功**。
4. 不宣传「完全匿名」；匿名提交默认关闭。
5. 桌面保险库仅文件权限是已知限制；移动端必须 Keystore/Keychain。
6. 公网部署必须 TLS/WSS。
7. 改动过 `./verify.sh --e2e`，并同步三层文档（L1 本文件 / L2 模块 README / L3 头注释）。
8. 不删除、不弱化既有安全测试。

## 文档

- `README.md`：总览、快速开始、能力矩阵
- `docs/architecture.md`：模块职责、密钥体系、加密方案与安全边界
- `docs/protocol.md`：HTTP 接口、WebSocket 编号、媒体与同步格式
- `docs/deploy.md`：部署、运维、备份、证书、故障速查
- `docs/clients.md`：桌面端与 iOS 的构建、签名、差异

## 开发环境提示

- **用 VS Code 看 Kotlin 代码请打开 `MIMCompose/` 子目录**。Gradle 工程根在那里；
  打开仓库根 `MIM` 时 Kotlin 扩展找不到 `settings.gradle.kts`，拿不到 classpath，
  同包引用（如 `escapeBack`）与 `kotlin.test.*` 会被标成未解析——那是误报，
  以 `cd MIMCompose && ./gradlew :composeApp:desktopTest` 的结果为准。
- Gradle 需要 JDK 17：本机默认 `java` 升到 25 后 Gradle 8.10 会解析不了版本号，
  用 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`。

## 当前边界

已完成：注册/登录/二次验证（含恢复码、邮箱找回、修改密码）、好友体系（申请/同意/
备注/删除/搜索/在线状态）、一对一文本 / 图片 / 语音 / 任意文件（端到端加密，服务端只存
密文，>8MB 自动分片）、撤回、未读已读、单方清除、离线队列、WebSocket 推送、
多设备历史同步（30 天 / 50 条，收发两端）、设备管理与注销、头像、
iOS 客户端（与桌面端功能对齐）、极光推送通道（服务端 + 客户端就绪）。
端到端回归脚本位于 `MIMCore/src/test/e2e`（只依赖 mim-core 与 JDK）。
未完成：群聊、全量历史搬迁、APNs（需付费开发者账号）。

[PROTOCOL]: Update this header on change, then check README.md
