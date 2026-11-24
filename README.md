# MIM

端到端加密的即时通讯：服务端只转发密文，看不到消息内容、会话对象和发送者。

```
┌──────────────┐   TLS/WSS    ┌──────────────┐
│ 桌面端 Compose│◀────────────▶│  服务端       │
│ iOS SwiftUI  │              │ Spring Boot  │
└──────┬───────┘              │ MySQL / Redis│
       │                      └──────────────┘
       │  X3DH + Double Ratchet（Rust，C ABI）
       └─ 每条消息按「目标设备」各加密一份，服务端只是搬运密文
```

## 快速开始

```bash
./start.sh            # MySQL → Redis → 服务端(TLS 8443)，已在跑的自动跳过
./start.sh --status   # 只看状态
./start.sh --stop     # 全部停掉
```

数据都在持久目录，重启机器不丢：

```
~/MIM-data/{mysql,tls,media,logs}     数据库 / 证书 / 媒体密文 / 日志
~/.mim/{keys,smtp.env,push.env}       派生密钥 / 邮件与推送凭据
```

客户端：

- **桌面端**：`./MIMCompose/run-desktop.sh`（见 `docs/clients.md`）
- **iOS**：XcodeGen 生成工程后签名安装（见 `docs/clients.md`）

## 能力

| 能力 | 状态 |
|---|---|
| 文字 / 图片 / 文件 / 语音 | ✅ 两端互通，附件端到端加密 |
| 实时收发（WSS）、未读、已读回执 | ✅ 轮询仅作兜底（20s） |
| 撤回、单方清除会话 | ✅ |
| 好友申请 → 同意 → 才能对话 | ✅ 搜索、备注、删除、在线状态 |
| 多设备历史同步（30 天 / 50 条） | ✅ 收发两端 |
| 二次验证（TOTP）、邮箱找回密码 | ✅ 阿里云邮件推送 |
| 离线推送（极光） | ⚠️ 服务端与客户端就绪，APNs 需付费开发者账号 |
| 群聊、全量历史搬迁 | ⬜ 未做 |

## 目录

```
MIMServer/     Spring Boot 服务端（接口、WebSocket、加密元数据）
MIMCoreRust/   密码学核心：X3DH / Double Ratchet / 状态加密（C ABI）
MIMKit/        iOS 客户端库（Swift Package）
MIMiOS/        iOS App（SwiftUI + XcodeGen）
MIMCompose/    桌面端（Compose Multiplatform，Kotlin）
tools/         备份等运维脚本
docs/          架构、协议、部署、客户端
```

## 文档

| 文档 | 内容 |
|---|---|
| `docs/architecture.md` | 模块职责、密钥体系、加密方案与安全边界 |
| `docs/protocol.md` | HTTP 接口、WebSocket 通知编号、媒体与同步格式 |
| `docs/deploy.md` | 部署、运维、备份、证书、常见故障 |
| `docs/clients.md` | 两端构建、签名、证书锁定、功能差异 |

## 安全模型（三句话）

1. 消息内容只有目标设备能解（端到端，服务端无私钥）；
2. 发送者身份由「发送者证书」自证，服务端不知道谁发的（sealed sender）；
3. 服务端能看到的只有：谁在收、发给哪台设备、密文大小与时间。

详见 `docs/architecture.md`。
