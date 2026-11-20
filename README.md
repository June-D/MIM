# MIM · 端到端加密私密通讯

[![CI](https://github.com/June-D/MIM/actions/workflows/ci.yml/badge.svg)](https://github.com/June-D/MIM/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Spring Boot 服务端 + Compose Multiplatform 桌面客户端（`MIMCompose`，当前主客户端）；
JavaFX 客户端（`MIMClient`）保留为互通参考。**只做一对一私密交流，不做群聊**。
服务端不持有私钥，只保存必要的路由元数据与密文；私钥与所有会话密钥永远只在客户端。

> 动手之前先读下面的《产品基调》。它是共识，不是建议。

## 产品基调（共识 · 所有开发者与 AI Agent 必须遵守）

这一节是产品的宪法。功能可以加，架构可以演进，但下面的定位与红线不得弱化。
与本节的冲突的改动，一律先停下来讨论，不要「顺手」改掉。

### 一、定位

**一款注重安全与隐私的个人端到端加密通讯产品。**

- 只做**一对一**私密交流，不做群聊、不做公开频道、不做内容推荐。
- 服务端是**不接触明文的转发与路由层**，不是数据拥有者。
- 个人使用优先：一个账号多台设备，每台设备独立密钥、独立会话。
- 不以「匿名」为卖点。匿名提交默认关闭，且即使开启也**不等于完全匿名**。

### 二、特点与基线

1. **安全**：私钥与所有会话密钥只在客户端；服务端不持有、也不请求。
2. **端到端加密**：X3DH + Double Ratchet，按设备建链，每条消息独立密钥。
3. **强大的安全内核**：密码学集中在 `MIMCoreRust`，业务层不得自行实现加密。
4. **可核对**：TOFU + 60 位安全码，双方可线下逐位比对。
5. **克制**：宁可少一个功能，也不加一条会泄露明文或扩大信任面的路径。
6. **诚实**：没接入的能力显式报错或返回空，绝不造假数据、绝不假装成功。

### 三、安全内核架构

```
Compose / Swift / Kotlin 业务层
        │  只做传输、状态、UI 语义；不实现密码学
        ├── JNI ──────► MIMCoreRust（唯一执行内核）
        └── C ABI ────► （iOS cinterop 预留，include/mim_core.h）
                            │
MIMCore（Java）────────────► 行为基准与测试向量（不进入生产执行路径）
MIMServer（Java）──────────► 鉴权、设备登记、预密钥分发、密文落库/转发、
                             发送者证书签发、清除点/已读/撤回状态、限流
```

内核职责（全部在 Rust）：

- X3DH（P-256）与 Double Ratchet 状态机；
- HKDF-SHA256、AES-256-GCM；
- 信封层组装/拆解、首条消息、sealed 收发、会话存储；
- 发送者证书验签（SHA256withRSA）；
- 加密状态落盘（`secure_store`）与设备密钥生成。

硬约束：

- **密码学只在 `MIMCoreRust` 实现**。Kotlin / Swift / Java 业务层不得写加密逻辑，
  不得持有明文私钥，不得绕开 JNI / C ABI 直接拼密文。
- `MIMCore`（Java）是**基准与向量来源**，不是生产执行路径。改协议时必须同时更新：
  向量 → Rust → 两端客户端 → 文档；`cargo test` 与 `tools/interop.sh` 必须对拍通过。
- 服务端只保存：账号、设备公钥、预密钥、消息密文、收发时间、已读/撤回/清除状态。
  新增任何字段都要论证「不泄露明文、不扩大可关联面」。
- 桌面保险库当前只靠文件权限（0600）是**已知限制**；移动端必须换 Keystore / Keychain，
  不得沿用文件权限方案。
- 公网部署必须 TLS / WSS；本地开发用 HTTP 只是为了调试。

### 四、产品功能

已完成（以 `main` 为准）：

| 类别 | 能力 |
|---|---|
| 账号 | 注册（用户协议 2026-11、可选找回邮箱）、登录、记住账号密码、退出登录、找回密码（需 SMTP） |
| 联系人 | 好友申请 / 同意 / 拒绝、备注、双向移除、本地屏蔽、搜索、未读角标 |
| 会话 | 一对一文本消息、撤回（一次性凭证）、已读回执、未读数、清除（单方 / 双方）、日期分组 |
| 可靠性 | 离线队列（只重发已组装密文，不重复推进棘轮）、未投递拉取、WebSocket 推送 + 轮询兜底 |
| 安全 | 每设备独立会话、多设备分发、发送者证书、安全码核对、设备管理（注销）、会话标签 |
| 内核 | Rust 内核 + JNI / C ABI、Java 基准对拍、状态加密落盘 |

明确不做：群聊、云端明文备份、服务端内容扫描、以匿名为主卖点。

尚未实现（不得在界面或文档中暗示已有）：附件 / 图片 / 语音、消息搜索、
自有设备间的历史同步副本、Compose 端 TOTP 登录、头像上传、移动端。

### 五、支持的客户端

| 客户端 | 目录 | 定位 | 状态 |
|---|---|---|---|
| PC 桌面 | `MIMCompose` | 当前主客户端，Compose Multiplatform + JVM 17 | 日常可用 |
| JavaFX 桌面 | `MIMClient` | 参考实现 / 互通与回归验证，Java 8 + JavaFX 8 | 保留，不再加新功能 |
| Android | — | 复用 Compose UI 与 JNI | 未交付 |
| iOS | — | 复用 C ABI 与 SwiftUI | 未交付 |

移动端不是「顺手加个 target」：需要系统密钥库、后台推送、电池与网络策略，
并重新验证会话状态落盘。未完成前不宣称支持手机。

### 六、产品的完成度

- **协议与服务端**：可运行，回归完整。服务端 210 项、协议核心 129 项、
  Rust 19 项、JavaFX 客户端 11 项；端到端 30 + 9 + 18 + 7 + 13 项。
- **PC 客户端**：可日常使用。注册到聊天、撤回、已读、未读、清除、推送、
  设备管理、安全码、屏蔽、备注、移除好友、记住账号均已打通；
  桌面测试 13 项（6 状态 + 1 本地加密 + 6 真实服务端联调）。
- **整体**：**约 0.7**。协议层与桌面端接近可用，移动端缺失、跨设备历史缺失、
  附件缺失、部署文档缺失，因此不称 1.0。

完成度只按事实写，不按「代码行数」或「功能数量」注水。

### 七、继续完善的内容（按优先级）

1. **移动端**：Android / iOS 工程、Keystore / Keychain、后台推送与断线重连。
2. **自有设备同步**：同账号另一台设备能看到已发消息；必须先定设备信任模型，
   避免被盗令牌注册的幽灵设备拿到明文。
3. **附件与图片**：仍然端到端加密，服务端只存密文；先定大小上限与存储策略。
4. **账号补全**：邮箱绑定入口、SMTP 配置、Compose 端 TOTP 登录。
5. **体验**：消息搜索、会话置顶、未读定位、安全码变更提醒。
6. **部署与运维**：TLS/WSS、限流、备份、密钥轮换、开源版部署文档（完成度达标后再写）。

### 八、给所有参与者（含 AI Agent）的红线

1. 不宣传「完全匿名」；匿名提交默认关闭，开启也需明确说明边界。
2. 未接入的能力必须显式失败或返回空，**禁止造假数据、禁止假装成功**。
3. 密码学只改 `MIMCoreRust`；改协议必须同步向量、两端客户端与文档。
4. 新增字段或接口必须论证不泄露明文、不扩大可关联面。
5. 不删除、不弱化既有安全测试；新能力必须带测试。
6. 改动必须通过 `./verify.sh --e2e`（无法运行时说明原因），并同步三层文档。
7. 界面文案不得承诺产品没有的能力；已知限制要写在用户能看到的地方。
8. 先读这一节，再读任务。与之冲突的需求，先提出来，不要默默改掉。

## 快速开始

前提：JDK 17（服务端与 PC 客户端）、MySQL 8、Redis 5+、Rust 工具链。
旧 JavaFX 客户端另需带 JavaFX 的 JDK 8，只用于互通参考。

```bash
# 1. 建库
mysql -uroot -e "CREATE DATABASE mim_core CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -uroot mim_core < MIMServer/sql/chat.sql
mysql -uroot mim_core < MIMServer/sql/e2ee.sql

# 2. 启动服务端（打包 → 复制到 /tmp → 等就绪；--stop 可停）
MIMServer/run-server.sh

# 数据库/Redis 不在默认地址时用环境变量覆盖，例如：
# MIM_DB_URL="jdbc:mysql://127.0.0.1:3306/mim_core?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8" \
#   MIM_DB_USERNAME=root MIMServer/run-server.sh

# 3. 启动 PC 客户端（自动构建带 JNI 的 Rust 动态库）
MIMCompose/run-desktop.sh
MIMCompose/run-desktop.sh --check          # 只检查环境

# 4. 回归
./verify.sh            # 编译 + 单元测试
./verify.sh --e2e      # 额外跑端到端（需 MySQL/Redis/服务端）
```

环境变量、TLS、多实例、限流、密钥轮换与升级补丁见
[`docs/operations.md`](docs/operations.md)。

## 架构

| 模块 | 技术 | 职责 |
|---|---|---|
| `MIMServer` | Spring Boot 3.5 / Java 17 | 鉴权、设备登记、预密钥分发、密文落库与转发、发送者证书、已读/撤回/清除、限流 |
| `MIMCoreRust` | Rust | 唯一密码学执行内核：X3DH、双棘轮、信封、会话存储、证书验签、状态加密；出口 JNI 与 C ABI |
| `MIMCore` | Java | 协议核心的行为基准与测试向量；不进入生产执行路径 |
| `MIMCompose` | Compose Multiplatform / JVM 17 | PC 主客户端：传输、本地加密缓存、状态与 UI 语义 |
| `MIMClient` | Java 8 / JavaFX 8 | 互通参考与回归验证；不再加新功能 |

数据流：客户端用 Rust 组装密文 → HTTP 提交 → 服务端只落库与推送 →
对端设备用 Rust 解密并写本地加密缓存。服务端全程不接触明文与私钥。

## 安全模型

- **X3DH + Double Ratchet**，按设备建链；每个设备独立身份密钥与棘轮状态。
- **发送者证书**是消息归属的唯一来源；服务端不保存发信人列。
- **TOFU + 60 位安全码**：首次对话前可线下逐位核对，不一致即停止。
- **撤回**只认一次性凭证的哈希；凭证随本机数据丢失而失效。
- **匿名提交默认关闭**，开启也不等于完全匿名（预密钥领取与多端同步仍可关联）。
- 服务端只保存：账号、设备公钥、预密钥、密文、收发时间、已读/撤回/清除状态。
- 桌面保险库仅靠文件权限（0600）保护密钥，是已知限制；移动端必须用系统密钥库。

协议细节见 [`docs/security.md`](docs/security.md) 与
[`docs/sealed-sender-design.md`](docs/sealed-sender-design.md)。

## 验证基线

| 范围 | 数量 |
|---|---|
| 服务端 | 210 |
| 协议核心 `MIMCore` | 129 |
| Rust 内核 `MIMCoreRust` | 19 |
| JavaFX 客户端 | 11 |
| Compose 桌面 | 13（6 状态 + 1 本地加密 + 6 真实服务端联调） |
| 端到端 | 30 + 9 + 18 + 7 + 13 |

`cargo test` 与 `MIMCoreRust/tools/interop.sh` 负责 Rust ⇄ Java 向量对拍；
未启用对应开关的 e2e 会明确 SKIP，不会伪装通过。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/security.md`](docs/security.md) | 加密方案、棘轮、TOFU、安全性质与已知局限 |
| [`docs/sealed-sender-design.md`](docs/sealed-sender-design.md) | 发送者证书与匿名提交的设计与实现差异 |
| [`docs/operations.md`](docs/operations.md) | 运行、建库、TLS、多实例、限流、密钥轮换、自测 |
| [`docs/server-api.md`](docs/server-api.md) | HTTP 接口与 WebSocket 通知类型 |
| [`docs/architecture.md`](docs/architecture.md) | 目录结构与模块职责 |
| [`docs/handoff.md`](docs/handoff.md) | 阶段结论、验收清单与待处理项 |
| [`docs/changelog.md`](docs/changelog.md) | 相对原始雏形的变更记录 |
| [`MIMCompose/README.md`](MIMCompose/README.md) | PC 客户端运行与接入说明 |

## 许可证

[MIT License](LICENSE) © 2026 June-D

界面使用系统字体；登录页背景为原创素材；`MIMClient/libs/` 下的 DataFX 8
快照 jar 仅用于 JavaFX 8 兼容，版权归其作者（Apache License 2.0）。
