# Sealed Sender 与元数据最小化：设计草案

> 状态：设计草案，尚未实现。先评审协议与威胁模型，再按阶段落地；
> 当前 main 的行为不变，所有消息仍由服务端记录 `from_user` / `from_device_id`。

## 1. 目标与非目标

### 目标

- 对**已建立会话**的普通消息，服务端不再知道「谁发的」，只保留收件人、
  目标设备、密文、时间与必要的投递状态。
- 收件人仍能确认消息来自哪个用户名/设备，并能用现有 TOFU 信任库校验身份公钥。
- 旧客户端、旧消息、撤回、历史、多端同步、离线补偿不能静默损坏。
- 分阶段上线，每阶段可独立回滚。

### 非目标

- 不隐藏收件人、目标设备、消息时间与粗略长度（长度已按 256 字符分桶）。
- 不抵抗全局流量分析、时序关联或服务端与网络合谋。
- 不改变端到端加密本身；sealed sender 只减少服务端可见的发送者元数据。
- 不在本阶段实现 cover traffic（假消息会消耗真实存储与配额，需单独设计）。

## 2. 当前元数据面

| 位置 | 当前内容 | 风险 |
|---|---|---|
| JWT | 用户名、设备、jti、版本 | 提交消息时服务端必然知道发送者 |
| `t_message` | `from_user`、`from_device_id`、`to_user`、`target_device_id`、`client_time`、`server_time`、握手公钥、密文 | 数据库/运维/备份可还原社交图 |
| `sender_identity_key` / `sender_ephemeral_key` | 明文列 | 可跨消息关联发送者，即使没有用户名 |
| 接收端 | 用 `fromUsername`/`fromDeviceId` 选择会话 | 一旦服务端不提供，需要新的会话索引 |
| 发送端历史 | 服务端按 `from_user` 查询 | 隐藏发送者后无法继续服务端查询 |
| 撤回 | 服务端按 `from_user` 判定 | 需要新的授权凭据 |

## 3. 威胁模型

- **诚实但好奇的服务端/运维/数据库备份**：不能从消息记录直接得到发送者。
- **被攻破的服务端**：仍能篡改/丢弃/延迟消息，也能拒绝服务；本设计不声称
  能防住主动恶意服务端。
- **收件人**：仍需要知道发送者；否则无法显示会话与执行 TOFU。
- **发送者身份证明**：最终由收件人用已固定的身份公钥验证，服务端证书只做
  「身份公钥 ↔ 用户名」的辅助映射，不能替代 TOFU。

## 4. 方案比较

### 方案 A：会话 ID（推荐先做）

- 每条已建立的会话有一个随机的 `sessionId`（32 字节），双方持久化在
  `SessionState` 中。
- 消息信封只带 `toUser`、`targetDevice`、`sessionId`、密文与时间；
  服务端不存 `from_user`/`from_device_id`。
- 收件人用 `sessionId -> (peer, peerDevice)` 索引选择棘轮，不需要服务端给发送者。
- 新会话第一条消息仍需走现有预密钥路径（服务端可见发送者），直到方案 B。
- 发送端自己的历史改为本地明文缓存；服务端不再提供「我发出的」查询。
- 撤回使用消息内的一次性 `recallTokenHash`，服务端只比对哈希，不知道发送者。

优点：改动集中在信封、会话索引、历史和撤回；不引入盲签名/证书。
缺点：只覆盖已建立会话；首次联系仍暴露发送者；发送端多端历史需要本地/同步方案。

### 方案 B：匿名令牌 + 发送者证书（后续）

- 客户端在认证状态下向服务端领取**盲签名令牌**（或批量随机令牌，明确记录
  其弱关联性），消息提交只带令牌，服务端无法把令牌映射回账号。
- 消息内（加密后）携带服务端签名的发送者证书：
  `username || identityKey || deviceId || expiry`。
  收件人用服务端公钥验证证书，再用 TrustStore 固定身份公钥；首次联系仍保留
  TOFU 风险，但服务端不再按消息记录发送者。
- 令牌签发有每账号/设备配额；消息提交按 IP/令牌/收件人配额限流。
- 需要解决：首次 X3DH 如何在不暴露发送者公钥的情况下完成、令牌盲签名实现、
  发送端历史、撤回、滥用治理。

### 方案 C：中继/混流

- 通过第三方中继剥离发送者；部署复杂、信任假设更多，本项目暂不采用。

## 5. 推荐路线

1. **Phase 0（本文）**：评审、确定威胁模型与兼容边界；加功能开关默认关闭。
2. **Phase 1：会话 ID 隐藏已建立会话的发送者**（见方案 A）。
3. **Phase 2：首次联系 sealed sender**（方案 B，盲签名令牌 + 证书）。
4. **Phase 3：cover traffic**（可选，需单独评估成本）。

## 6. Phase 1 详细设计

### 数据模型

- `t_message`
  - `from_user` / `from_device_id` 改为 **NULL 允许**；sealed 消息写 NULL。
  - 新增 `sealed` tinyint、`session_id` varchar(64)、`recall_token_hash` varchar(64)。
  - 保留 `to_user` / `target_device_id` 用于投递与收件人历史；
    索引仍以收件人为主。
- `t_message` 的现有唯一索引包含 `from_user`；sealed 消息需要新增
  `(to_user, target_device_id, session_id, client_msg_id)` 幂等键。
- `SessionState` 新增 `sessionId`，随会话文件加密保存；旧会话首次发送时生成。

### 客户端

- `MessageService.send`：
  - 已有会话：把 `sessionId` 放进信封，设置 `sealed=true`，不设置发送者字段。
  - 新会话：沿用现有 `/prekey` + 发送者字段（Phase 1 兼容路径）。
  - 本地明文缓存保留 `peer/device/clientMsgId`，作为发送端历史来源。
- `MessageService.decrypt`：
  - 维护 `sessionId -> (peer, peerDevice, state)` 索引；先按 sessionId 找会话。
  - 旧消息没有 sessionId 时按现有 `fromUsername/fromDeviceId` 路径。
  - 首次收到 sealed 消息时，从解密后的 sealed 头读取发送者证书/身份公钥，
    执行现有 TrustStore 校验。
- 撤回：
  - 发送方为每条消息生成随机 `recallToken`，服务端只存哈希；
    撤回请求带 `clientMsgId + recallToken`，服务端比对哈希后执行。
  - 接收方不需要 token；撤回后仍能更新 UI。
- 多端同步：
  - 发送端其它设备的同步副本使用各自的会话/sessionId；服务端仍只按目标设备路由。
  - 发送端历史从本地明文缓存渲染；新设备首次同步仍走用户批准的设备历史同步。

### API

- `POST /message`：sealed 消息携带 `X-Sealed-Session`/`sessionId` 字段，不要求
  发送者字段；服务端从 JWT 只拿到「提交者」用于限流，但不落库为 from_user。
  > 注意：Phase 1 服务端在提交时仍能通过 JWT 知道提交者；真正对服务端隐藏
  > 提交者需要 Phase 2 的匿名令牌。Phase 1 主要消除**持久化元数据**，
  > 不应对外宣称完全 sealed。
- `GET /message/history`：sealed 消息只对收件人按 `to_user` 返回；发送端改用本地历史。
- `POST /message/recall`：接受 `recallToken`，不再依赖 from_user 判定。
- 旧接口保持兼容；`sealed` 缺省为 false。

### 服务端校验

- 继续校验设备、目标设备、密文长度、时间窗口、限流。
- 对 sealed 消息：
  - `from_user`/`from_device_id` 必须为空；
  - `sessionId` 格式/长度校验；
  - 幂等键使用 to_user + target + sessionId + clientMsgId；
  - 撤回只接受哈希匹配的 token；
  - 不把 JWT 用户名写入消息记录（只在请求级限流/审计计数中使用，且不写日志）。

### 兼容与回滚

- 功能开关 `mim.sealed-sender.phase1=false`，按用户/版本灰度。
- 旧客户端继续走非 sealed 路径；旧消息字段不变。
- 回滚只需关闭开关；已写入的 sealed 消息仍可被收件人解密，
  发送端本地历史不受影响。
- 数据库变更只允许 NULL / 新增列，不做破坏性修改。

### 测试计划

- 单元：sessionId 生成/持久化、sealed 信封序列化、撤回 token 哈希。
- 集成（MySQL）：sealed 消息落库后 `from_user` 为 NULL；幂等并发；
  收件人历史/离线补偿；撤回 token 正确/错误/重放。
- 客户端：旧消息兼容、新会话兼容、多设备、历史同步、本地发送历史。
- e2e：新旧客户端混跑；断言服务端数据库/日志不出现 sealed 消息的发送者；
  抓包确认信封不含 fromUsername/fromDeviceId。
- 回归：现有 184 服务端 / 104 客户端 / 14+13 e2e 全部保持绿。

## 7. Phase 2 待决问题

1. 盲签名方案（RSA blind / VOPRF）与密钥轮换。
2. 首次 X3DH 如何在证书加密的同时完成会话建立。
3. 令牌签发配额、滥用治理与收件人拉黑。
4. 发送端历史、撤回、账号删除后的元数据清理。
5. 恶意服务端伪造发送者证书的信任锚与客户端公钥固定。
6. 与 TOFU/安全码的关系：证书只辅助映射，不能替代线下核对。

## 8. 结论

先做 Phase 1 的「会话 ID + 不落库发送者」是投入产出比最高的一步：
它消除数据库/备份中的持久化发送者元数据，同时保留现有 E2EE、TOFU、多设备
与撤回能力。真正的「提交时对服务端匿名」需要 Phase 2 的令牌/证书体系，
不应在 Phase 1 中假装完成。
