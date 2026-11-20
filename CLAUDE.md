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

- `README.md`：产品基调、快速开始、架构、验证基线、文档索引
- `docs/security.md`：加密方案与安全性质
- `docs/sealed-sender-design.md`：发送者证书与匿名提交设计
- `docs/operations.md`：运行、建库、TLS、多实例、限流、密钥轮换
- `docs/server-api.md`：HTTP 与 WebSocket 契约索引
- `docs/architecture.md`：目录结构
- `docs/handoff.md` / `docs/changelog.md`：阶段结论与变更记录

## 当前边界

已完成：注册/登录/二次验证（登录与开启管理）/记住账号、好友申请与备注、屏蔽、双向移除、
一对一文本 / 图片 / 语音（媒体端到端加密，服务端只存密文）、
撤回、已读、未读、清除、离线队列、WebSocket 推送、设备管理、安全码与变更提醒、
本地消息搜索、会话置顶、未读定位。
端到端回归脚本位于 `MIMCore/src/test/e2e`（只依赖 mim-core 与 JDK）。
未完成：移动端、自有设备历史同步、任意文件附件、头像上传、部署文档。

[PROTOCOL]: Update this header on change, then check README.md
