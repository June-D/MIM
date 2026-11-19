# Sealed Sender 与元数据最小化：设计草案

> 状态：Phase 1 已落地，采用 **HMAC 盲引用** 的兼容实现（见第 9 节）。
> 首次联系、提交时匿名、撤回 token 的严格形态仍属于后续阶段；
> 不要把 Phase 1 宣传为完全 sealed sender。

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

> 本节保留最初的设计意图（`from_user` 置 NULL、recall token）。
> 实际兼容实现使用盲引用与 JWT→盲引用比对撤回，差异见第 9 节。

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

> **历史记录**：第 9、10 节描述 Phase 1 / 1.5 的盲引用方案，已在
> 第 12 节的协议断代中整体删除。当前实现请直接看第 11、12 节。

## 9. Phase 1 实现状态（盲引用兼容方案）

实际实现没有把 `from_user` / `from_device_id` 置为 NULL，而是：

- 已建立会话的消息写入 `sealed=1`、`session_id`，并把 `from_user` /
  `from_device_id` 写成 **HMAC-SHA256 盲引用**（64 位十六进制），不写用户名和设备号。
- 历史、未读、撤回、会话清除和多端同步继续使用盲引用查询；服务端在提交时
  仍从 JWT 知道提交者，因此这不是「提交时匿名」。
- 旧消息和新客户端首次桥接消息仍然保留明文 `from_user` / `from_device_id`。
- 盲引用密钥优先取 `MIM_SECURITY_METADATA_KEY`（Base64 32 字节），未配置时
  退化为 JWT 私钥；JWT 私钥轮换会使旧 sealed 历史索引失效，当前没有轮换流程。
- 功能开关 `mim.sealed-sender.enabled`（环境变量
  `MIM_SEALED_SENDER_ENABLED`）默认开启；关闭后 sealed 请求降级为旧格式
  存储（不隐藏发送者，但不会中断已切换的会话），旧客户端仍可走非 sealed 路径。

### 会话建立与切换

1. 发起方生成 `sessionId`，随首条 X3DH 消息以旧格式发送（此时服务端仍能看到
   发送者）。
2. 接收方解密后把 `sessionId` 写入应答链并持久化，回一条带同一
   `sessionId` 的 sealed 消息。
3. 发起方收到回包后确认 `sessionId` 已被对方知晓，下一条消息才 sealed。
4. 升级前已存在的会话没有 `sessionId`：先发一条带 ID 的旧格式桥接消息，
   确认后再切换；对端是旧客户端时会一直停留在桥接模式。

### 客户端

- `SessionState` 新增 `sessionId` / `sessionIdConfirmed`，随会话文件加密保存；
- `PeerCipher` 记录最近成功解密的链，收件端按 `sessionId` 索引棘轮；
- 发送端本地明文缓存按 `peer + clientMsgId` 保存，服务端历史用
  `fromMe` 标记区分收发；发送端历史不依赖服务端明文 `from_user`；
- `MessageService.decrypt` 对 sealed 消息优先使用本地发送缓存，收件消息才推进棘轮。

### 与原草案的差异

- 原草案要求 sealed 消息的 `from_user` 为 NULL，并使用一次性 `recallToken`；
  Phase 1 的兼容实现使用盲引用和 JWT 用户名 → 盲引用比对完成撤回授权。
  这样历史、未读、同步和撤回不需要重写查询层，但会留下**可被持有服务端密钥者
  关联的伪随机发送者索引**。
- Phase 1.5 已按草案补上 `from_ref`/`from_device_ref` 独立列与一次性 token 撤回，
  详见第 10 节；第 9 节保留为 Phase 1 的历史记录。

### 验证

- 服务端 186 项（含真实 MySQL 集成）、客户端 107 项；
- e2e：多设备 26 项（含 sealed 往返、历史、撤回）+ WebSocket 13 项；
- 新客户端对旧客户端保持桥接模式；旧客户端对 sealed 服务端仍走明文路径。

> **历史记录**：本节能力（标签、跨设备撤回、投递即擦除）已并入第 12 节的
> 单一路径；`from_ref` / `from_device_ref` 已被删除。

## 10. Phase 1.5 实现状态（发送者列置 NULL）

Phase 1.5 按第 6 节草案补齐两件事：发送者列不再承载盲引用，撤回不再依赖
发信人引用。已经落地并验证，但**提交匿名仍未实现**。

### 数据模型

```sql
from_user        varchar(64) NULL   -- sealed 消息写 NULL
from_device_id   varchar(64) NULL   -- sealed 消息写 NULL
from_ref         varchar(64) NULL   -- sealed 发信人 HMAC 盲引用
from_device_ref  varchar(64) NULL   -- sealed 发信设备 HMAC 盲引用
recall_token_hash varchar(64) NULL  -- 一次性撤回凭证的 SHA-256
UNIQUE KEY uk_client_target (client_msg_id, target_device_id)
```

- 唯一键不再包含发送者。sealed 消息本来就没有明文发送者，把它留在唯一索引里
  只会让幂等语义依赖盲引用。
- `idx_ref_conversation(from_ref, to_user, id)` 和
  `idx_ref_unread(from_ref, to_user, read_flag)` 支撑发件方向的历史与未读；
  收件方向仍按 `(from_ref, to_user)` 查询，因为收件人列仍是明文用户名。

### 服务端

- 发送：`sealed=1` 时 `from_user`/`from_device_id` 写 NULL，盲引用写独立列；
  同一条逻辑消息的所有设备副本共用同一份 `recall_token_hash`。
- 历史：先按旧格式 `from_user` 查询，再分别按「我的引用 → 对端用户名」和
  「对端引用 → 我的用户名」查询，结果按 id 合并；`fromMe` 由方向决定。
- 未读/已读/清除：旧格式走原查询，sealed 走 `ByRef` 变体。
- 未投递：清除点的 peer 解析改为
  `if(from_user = toUser, to_user, coalesce(from_ref, from_user))`；
  收件 sealed 副本按 `from_ref` 命中清除点。
- 同步：`selectParticipantCopy` 同时接受用户名和盲引用，避免把同步副本当原文。
- 能力回传：`KeyBundle` / 预密钥包带 `supportsRecallToken`，供发送端判断
  目标设备能否接收带凭证的同步副本。
- 撤回：`selectOwnedCopy` 接受用户名或盲引用；消息有 `recall_token_hash` 时
  必须提交匹配的 `recallToken`，只用 `recallByToken` 更新；
  没有哈希的旧消息回退到 `recallBySender`。
- 握手参数的生命周期：按请求落库（首条消息必须带），
  `markDeliveredUpTo` 在标记已投递的同一条 UPDATE 里把
  `sender_identity_key` / `sender_ephemeral_key` / `consumed_opk_id` /
  `recipient_spk_id` 清空。身份公钥可 join `t_user_key.public_key`
  反查用户名，因此不能长期保留；已建立会话的新客户端也不再发送这些字段。

### 客户端

- 每条逻辑消息生成 32 字节随机 `recallToken`（Base64），随所有副本提交；
- `PlaintextStore` 新增 `token|peer|clientMsgId` 加密项；
- `MessageService.recall(peer, clientMsgId)` 从本地取 token，放进请求体；
- 历史同步转发「自己发出的」消息时，用 `SyncEnvelope` 把凭证包进密文；
  接收设备解密后拆包，正文上屏、凭证落盘（详见下方「跨设备撤回」）；
- 服务端 `recallToken` 是 `WRITE_ONLY`，`recallTokenHash` 是 `@JsonIgnore`，
  响应里不会回显凭证或哈希。

### 跨设备撤回（凭证同步）

撤回凭证以发送设备为源。另一台设备要能撤回同一条消息，必须拿到同一份凭证，
而服务端只存哈希——所以凭证只能随**已加密**的数据走：

1. 设备上传公钥时声明 `supportsRecallToken`，服务端存
   `t_user_key.supports_recall_token`，并在预密钥包里回传；
2. 历史同步向目标设备转发时，若目标设备声明了该能力，就把凭证与正文打包成
   `SyncEnvelope`，整体交给双棘轮加密，再上送 `/message/sync`；
3. 接收设备解密后拆信封：正文显示给界面，凭证写进本地
   `token|peer|clientMsgId` 加密项；本地明文缓存仍保存未拆包原串，
   因为要用同一串内容重新验证转发设备的签名；
4. 该设备之后调用 `/message/recall` 时就能带上凭证，服务端比对哈希后放行。

兼容与边界：

- 信封以 `U+0001` 魔数开头并校验凭证字符集，不满足就当普通正文，绝不误判；
- 未声明能力的旧客户端只收纯文本副本，行为与升级前完全一致；
- 能力位变更需要当前密码，防止只凭被盗 JWT 把设备标成支持新能力；
- 凭证只在「源设备还持有它」且「跑过历史同步」时才送达；
  本机数据被清除或从未同步的设备仍只能走旧版兼容撤回路径。

### 迁移与兼容

- `MIMServer/sql/e2ee.sql` 末尾提供取消注释即用的升级补丁，顺序固定：
  1. 加 `from_ref` / `from_device_ref` / `recall_token_hash`；
  2. 把 `sealed=1` 行的 `from_user` / `from_device_id` 回填到新列；
  3. 修改 `from_user`/`from_device_id` 为可空；
  4. 换成不含发送者的唯一索引，并加两个盲引用索引；
  5. 把 `sealed=1` 行的发送者列置 NULL。
- 跨设备撤回另有一条独立的 `ALTER`：`t_user_key` 加
  `supports_recall_token tinyint(1) NOT NULL DEFAULT 0`。只加列即可，
  没有历史数据需要回填；旧设备默认 0，行为与升级前一致。
- 不回填就置 NULL 会让旧 sealed 历史在迁移后全部查不到。
- 旧唯一索引换成新唯一索引前，先跑注释里的重复检查；若存在
  「同一 clientMsgId + 目标设备」的多行数据，`ADD UNIQUE` 会失败。
- 旧消息没有 `recall_token_hash`，撤回继续走「JWT 用户名 / 盲引用」比对；
  新旧客户端可以混跑，不需要一次性全部升级。
- 本地 MySQL 旧库（10 条 sealed 消息）已按上述顺序迁移成功：sealed 行
  `from_user` 全为 NULL、`from_ref` 全部回填，唯一索引与两个新索引生效。

### 安全边界

- 这是「数据库发送者列无明文、无固定索引」，不是提交匿名：服务端提交时
  仍从 JWT 知道发送者，盲引用也可被持有服务端密钥者关联。
- 数据库中与发送者相关的列只剩：盲引用（需服务端密钥才能关联）、
  密钥版本号、客户端时间。握手参数只在投递前存在，确认投递后即被清空，
  因此长期数据不能用「公钥目录 join」反查用户名。
- 撤回凭证保存在发送端本地；另一台设备只有在历史同步时收到信封才有凭证，
  本机数据丢失或从未同步的设备只能走旧版兼容撤回路径。
- 首次联系与桥接消息仍是旧格式，发送者可见，直到 Phase 2 的令牌/证书体系。

### 验证

- 服务端 227 项：新增 `RecallTokenTest`、sealed 落库、唯一索引、按引用历史/
  未读、token 撤回、Phase 1 存量行兼容与清除点过滤；
- 客户端 128 项：`recallToken` 落盘、撤回与证书两类信封、证书验签与公钥固定；
- e2e 多设备 30 项：新增「响应不回显撤回凭证」「缺 token 撤回 sealed 被拒」与
  「预密钥包声明支持跨设备撤回凭证」，
  WebSocket 13 项不受影响。

## 11. Phase 2 决策稿（待确认后实施）

Phase 1.5 消除的是**持久化**发送者元数据。首次联系与桥接消息仍把
`from_user` 写成明文，服务端提交时也知道发送者。Phase 2 要解决这两件事，
但它们可以拆开交付，风险完全不同。

### 威胁模型

新增的对手是「能读数据库、能改服务端响应，但没有客户端私钥的人」。
目标：

1. 首条消息落库后，数据库里没有可反查的用户名（与 Phase 1.5 一致）；
2. 收信端仍能确认「这条消息来自谁」，且这个归属不可被服务端伪造；
3. 恶意用户不能冒充他人发消息，也不能无限量匿名投递。

### 三块机制

| 机制 | 解决什么 | 是否引入匿名 |
|---|---|---|
| 发送者证书（服务端签名，短有效期） | 收信端从密文里学到发送者身份 | 否 |
| 首条消息 sealed（证书随密文走） | 首条消息不落 `from_user` | 否 |
| 匿名提交令牌（盲签名） | 提交时服务端不知道是谁 | 是 |

证书与令牌必须分开：证书回答「这条消息是谁发的」（给收信端看），
令牌回答「提交者是不是一个真实账号」（给服务端看）。混在一起会让
服务端在签发时就知道收发关系。

### 分步计划

**2a（已落地，默认关闭）：证书签发与验证**
- 服务端用独立密钥签名 `{username, deviceId, identityKey, issuedAt, expiresAt, keyId}`；
  `username` 取自 JWT，客户端无法伪造。
- `GET /cert/key` 返回公钥与 keyId；客户端首次拉取后 pin 住（TOFU），
  之后公钥变化必须显式确认，否则拒绝验证证书。
- 开关 `MIM_SENDER_CERT_ENABLED`，默认关闭；关闭时接口返回「未启用」且不生成密钥。
- 实现在 `SenderCertServiceImpl`：密钥存 `t_rsa_key`
  （`MIM:AUTH:SENDER-CERT:PRI/PUB`，主密钥加密），与 JWT 密钥分离，
  首次使用时惰性生成；有效期 24 小时。
- 验签用的规范化串见 `SenderCertificate.canonical()`；
  客户端 `SenderCertificateView.canonical()` 必须与它逐字节一致
  （客户端 `SenderCertVerifierTest` 用真实 RSA 签名覆盖这条契约）。
- 测试：`SenderCertServiceTest` 5 项（签名可验、篡改三类字段被拒、
  有效期 24h、关闭时不产生密钥、密钥只生成一次）；
  `HttpApiIntegrationTest` 1 项（默认关闭返回未启用）。
- 此步不改变消息格式，也不宣称匿名。

**2b（已落地，默认关闭）：首条消息 sealed**
- 发送端把证书放进 `SenderCertEnvelope`，随 X3DH 密文提交；
  服务端对首条消息也写 `sealed=1`、`from_ref`，`from_user` 为 NULL。
- 接收端顺序固定：先用握手参数建临时应答链解密 → 拆信封 →
  证书验签（`SenderCertVerifier`）→ 校验证书身份公钥与握手参数一致 →
  按 TOFU 校验设备公钥 → 才把会话落到真实 peer/device 槽位。
  任一步失败都按不可解密处理，绝不猜发送者。
- 信任锚：`ServerCertPin` 首次拉取 `/cert/key` 即固定到
  `~/.mim/server-cert.pub`（公钥是公开信息，明文存储）；之后服务端换钥
  一律保留旧值并告警，新证书会验签失败。
- 兼容：只有对端设备声明 `supportsSenderCert` 时才走新格式，
  否则保持明文发送者；`from_ref` 历史查询已就绪，不需要改 SQL。
- 握手参数的生命周期：首条消息必须带，`markDeliveredUpTo` 在确认投递时
  连同身份公钥一起清空，长期数据只剩盲引用。
- 已实现文件：`crypto/SenderCertEnvelope`、`crypto/SenderCertVerifier`、
  `crypto/ServerCertPin`、`service/SenderCertService`；
  发送与解密接在 `MessageService.send` / `decryptFirstContact`。
- 验证：`SenderCertE2eTest` 11 项已覆盖证书签发/独立验签、首条 sealed
  不落 from_user、收信端按证书还原、投递后擦除、收信端历史可查。
- **未验证项**：该 e2e 用真实双棘轮模拟两端，但不走
  `MessageService` 的发送/解密接线；上线前必须人工验证首次联系、
  TOFU 弹窗、会话丢失提示三条路径。

**2c 前置：会话标签（进行中）**
- 为什么必须先做：匿名提交时服务端不知道发送者，无法计算
  `HMAC(用户名) → from_ref`；收信端要按「与某人的会话」查历史，
  只能由客户端提供一个不透明、双方共享的标签。
- 格式与分发：24 字节随机 → Base64URL（32 字符，`^[A-Za-z0-9_-]{24,64}$`）。
  由发起方在首次联系时生成，放进证书信封（不增加消息字段）；
  收信端解密后加密存本地，之后两个方向的每条消息都带上该标签。
- 服务端：`t_message.conversation_tag` + `(tag, id)`、`(tag, to_user, id)` 索引；
  标签用 `@JsonProperty(WRITE_ONLY)`，请求可带、响应绝不回显（旧客户端安全）。
- 当前阶段（双写）：历史/未读/已读/清除/未投递/跨设备同步**都已支持标签**，
  但 `from_ref` 仍在写，两条查询路径并存：
  - 未读用 `countUnreadByRefOrTags` 合并去重，避免同一条消息算两次；
  - 清除同时写 `tag:<tag>` 清除点，未投递用
    `greatest(引用游标, 标签游标)` 过滤；
  - 同步用 `selectParticipantCopyByTag` 在引用路径失配时兜底。
  双写期不产生隐私收益，只是把切换所需的查询路径全部铺好。
- **已停写 `from_ref`（带标签的消息）**：`send` 在 `conversationTag` 非空时
  不再写 `from_ref`/`from_device_ref`。这些消息的历史、未读/已读、清除、
  未投递、同步、撤回全部走标签：
  - 撤回：`selectOwnedCopyByTags` + 请求里的 `tags`（仍需 token 授权）；
  - 历史：`selectTagHistoryForDevice` 收件侧优先取「发给本设备」的副本，
    否则多设备会拿到别人的密文；
  - 同步：副本方向按 `to_user` 推断（标签消息没有发送者列）。
- 兼容规则：**不带标签的消息仍写盲引用**。旧客户端不认识标签，
  它参与的会话也不会产生标签，所以两条路径各自封闭、互不影响。
  代价是混合版本的多设备用户：新设备发起的会话被标签化后，
  旧设备的历史里看不到被标签化的消息。
- 旧 ref 分支已在 Phase 2 协议断代中随旧数据一起删除，见第 12 节。
- 风险：标签只存在本地，本机数据被清后该会话的历史会少一段；
  切换前保留盲引用正是为了兜住这种情况。标签被错误复用时，
  查询用 `to_user IN (会话双方)` 限定，避免串会话。

**2c：匿名提交（已落地，默认关闭，边界明确）**
- 机制：`/token/key` 分发盲签名公钥 → 客户端致盲 nonce →
  `POST /token/blind`（需 JWT，按账号计配额）→ 客户端去盲得到一次性令牌 →
  `POST /message/anonymous`（`X-Sender-Token`，**不带 JWT**）提交带标签的 sealed 消息。
- 防滥用三件套均已实现：Redis 每日配额（500/账号）、令牌时间窗（±15 分钟）、
  消费一次性（`SETNX`，TTL 30 分钟）。Redis 不可用时**失败关闭**。
- 服务端落库：`from_user` / `from_device_id` / `from_ref` / `from_device_ref` 全为 NULL，
  只保留客户端生成的会话标签与 `session_id`。不向发送者其它设备推送。
- 密码学：RSA-2048 盲签名 + 全定义域哈希（FDH），服务端看不到 nonce，
  因此无法把令牌映射回账号。**该实现未经外部审计**，生产启用前必须评审。

**2c 的真实边界（不要过度宣传）**

1. **首次联系的意图仍可见**：客户端必须用 JWT 调 `/prekey/{username}` 领取预密钥包，
   服务端因此知道「账号 A 想联系 B」。匿名提交只隐藏「哪条密文由谁提交」。
2. **多端同步仍会关联**：`/message/sync` 需要 JWT，并把会话标签带给服务端，
   足以把标签映射回账号。要做到完全不可关联，同步必须改成设备直连（未实现）。
3. **收件人拉黑只能由客户端做**：服务端不知道发送者，无法在服务端拦。
   已实现：`PlaintextStore` 的 `blocked|peer` 加密名单 + 解密出口统一屏蔽；
   被屏蔽会话不参与跨设备历史同步。服务端仍会投递并计未读。
4. **OPK 未在匿名路径消费**：没有账号可匹配 `claimed_by`，预留的 OPK 依赖 TTL 释放。
5. **客户端已接入但默认关闭**：设置 `-Dmim.anonymousSend=true` 且会话已有标签时，
   `send` 先申请令牌再走 `/message/anonymous`；令牌申请失败、服务端未启用或
   被拒都会**回退普通发送**。该路径尚未在真实界面上验证，启用前必须人工跑
   首次联系、降级与多设备三条路径。

### 必须由产品确认的问题

1. 首条消息是否允许「陌生人可发」？若不允许，2b 需要先做联系人白名单，
   否则等于把垃圾消息面放大。
2. 证书有效期与轮换周期（建议 24 小时 + 客户端时钟偏差容忍 5 分钟）。
3. 2c 是否进入本期范围；若进入，令牌配额按账号还是按设备计。

### 验收标准

- 2a：签发→客户端验证往返、篡改任一字段必须失败、过期必须失败、
  pin 变化必须阻断；证书公钥与 JWT 密钥分离。
- 2b：首条消息 `from_user` 为 NULL、`from_ref` 有值；收信端能按证书还原对端；
  伪造证书（换 username 或换 identityKey）必须被拒；旧客户端仍收到明文格式。
- 2c：重放同一令牌必须被拒；配额耗尽返回明确错误；拉黑生效。

### 回滚

三步都有独立开关。关闭 2b 后，新消息回到明文发送者格式，已落库的 sealed
首条消息仍可读；关闭 2a 后，客户端不再请求证书，但已 pin 的公钥保留。

## 12. Phase 2 协议断代（发送者列彻底移除）

为了不再留任何「为旧协议服务」的分支，本版本做了一次**不可逆**的协议断代：

### 删除了什么

- `t_message.from_user` / `from_device_id` / `from_ref` / `from_device_ref`
  四列，以及 `idx_conversation` / `idx_ref_conversation` / `idx_ref_unread`；
- `MetadataRef`（HMAC 盲引用）与 `MIM_SECURITY_METADATA_KEY`；
- `send` 的明文发送者与「无标签降级」分支、`shouldSeal`、旧算法
  `ECDH-P256-HKDF-...`、`markFromMe`、`recallBySender`、ref 历史/未读/已读/
  清除/未投递/同步查询，以及对应的单测与 e2e 断言；
- 向发送者其它设备推送原始密文：它们本就解不开，多端历史统一走设备间同步。

### 现在只有一条路径

- `send` / `sendAnonymous` 都要求 `sealed=true` + 合法 `conversation_tag`；
- 历史/未读/已读/清除/未投递/同步/撤回全部按标签；
- 撤回只认 `recallToken` 的哈希；没有 token 的消息可以发送，但无法撤回；
- 同步副本的 `to_user` 改写为本账号（未投递才能命中），
  归属由 `sync_original_*` 签名还原；
- 发送者证书成为必需：`MIM_SENDER_CERT_ENABLED` 默认 `true`，
  关闭后客户端会明确报错而不是降级。

### 升级步骤（必须按顺序，且不可逆）

`MIMServer/sql/e2ee.sql` 末尾的补丁（默认注释）：

1. `CREATE TABLE t_message_legacy_archive LIKE t_message`；
2. 把 `conversation_tag IS NULL` 的行复制进归档表；
3. 从 `t_message` 删除这些行；
4. `DROP INDEX` 三个旧索引 + `DROP COLUMN` 四个发送者列；
5. 删除 `t_conversation_clear` 中非 `tag:` 前缀的清除点。

标签是客户端随机生成的，服务端无从推导，**旧消息不可能保留在聊天记录里**；
需要留证时必须先自行备份归档表。

### 代价（已知且接受）

- 旧客户端完全不可用：它们发出的消息没有标签，会被直接拒绝；
- 混合版本多设备中，旧设备看不到新会话的历史；
- 没有 `from_ref` 后，跨设备同步与撤回都依赖标签；
  标签丢失（本机数据被清）会永久失去该会话的历史与撤回能力。
