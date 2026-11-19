# MIM · 端到端加密私密通讯

[![CI](https://github.com/June-D/MIM/actions/workflows/ci.yml/badge.svg)](https://github.com/June-D/MIM/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Spring Boot 服务端 + JavaFX 桌面客户端。**只做一对一私密交流，不做群聊**。
服务端不持有私钥，只保存必要的路由元数据与密文；私钥与所有会话密钥永远只在客户端。

## 当前状态与交接（2026-11）

当前 `main` 已包含 sealed sender Phase 1.5：

- 当前代码：`main` HEAD（以 `git log -1` 为准；Phase 1 初版为 `a2b6458`）
- CI：见顶部 badge；最近一次服务端/客户端/e2e 全绿
- 回归基线：服务端 **234** 项、客户端 **134** 项、e2e **30 + 18 + 7 + 13** 项

### 已完成

- 服务端 Java 17 / Spring Boot 3.5.16；客户端仍为 Java 8 + JavaFX 8。
- X3DH + Double Ratchet、按设备多端、TOFU/60 位安全码、OPK 预留后消费。
- 2FA、恢复码、邮箱/TOTP 密钥轮换、JWT 主密钥加密、Redis 安全边界、WS 过期断连。
- 消息长度填充（能力协商）、跨设备历史同步、sealed sender Phase 1.5（含跨设备撤回）。
- Phase 2a/2b：发送者证书签发、分发、TOFU 固定，并接入首条消息（默认关闭）。
- Phase 2c：盲签名令牌签发/消费 + 匿名提交（服务端与客户端都已接入，默认关闭）。
- Maven Wrapper 3.9.9；CI 覆盖单元、真实 MySQL 集成和 e2e。

### Sealed sender 的实际边界（Phase 1.5）

Phase 1.5 让 `from_user` / `from_device_id` 对 sealed 消息真正置 NULL：

- 已建立会话的消息写 `sealed=1`、`session_id`，`from_user` / `from_device_id` 为 NULL；
- HMAC-SHA256 盲引用改存独立列 `from_ref` / `from_device_ref`，历史、未读、
  清除、多端同步按盲引用查询，旧消息仍按 `from_user` 兼容；
- 唯一索引改为 `(client_msg_id, target_device_id)`，发送者不再参与幂等判定；
- 撤回改用发送时生成的一次性 `recallToken`，服务端只保存 SHA-256；
  没有凭证哈希的旧消息回退到「JWT 用户名 → 盲引用」授权；
- 同账号另一台设备可通过历史同步拿到凭证并撤回同一条消息，
  能力位 `supportsRecallToken` 决定是否下发带凭证的加密副本；
- 首条消息在双方都升级且服务端开启 `MIM_SENDER_CERT_ENABLED` 时也 sealed；
  任一条件不满足就退回旧格式（服务端可见发送者），绝不阻断首次联系；
- 握手参数只活到投递：首条消息必须带，收信设备确认收到后服务端立刻擦除
  身份公钥/临时公钥/OPK 编号，避免用「公钥目录 join」反查用户名；
- 首条消息可带服务端签发的发送者证书（2b）：证书在密文里，服务端仍不知道归属；
- 会话标签随证书信封送达（2c 前置）：客户端生成、双方共享，服务端只当不透明值；
  当前是双写期——标签与 `from_ref` 并存，历史同时按两者查询并去重；
- 服务端提交时仍从 JWT 知道提交者。**不要宣传为提交匿名或完全 sealed**；
- 提交匿名仍是 2c：服务端提交时仍从 JWT 知道发送者；
  2b 只让「已落库的首条消息」不再写 from_user。

## 交接给下一个 Agent

### 建议接手顺序

1. **先读**：
   - `docs/sealed-sender-design.md`（重点第 9、10、11 节，实际实现与草案差异）
   - `MIMServer/src/main/java/com/junesoon/mim/service/impl/MessageServiceImpl.java`
   - `MIMClient/src/main/java/com/junesoon/mim/service/MessageService.java`
   - `MIMServer/src/main/java/com/junesoon/mim/service/impl/SenderCertServiceImpl.java`
     与 `responses/SenderCertificate.java`（2a 的规范化串）
   - `MIMClient/src/main/java/com/junesoon/mim/service/HistorySync.java` 与
     `MIMClient/src/main/java/com/junesoon/mim/crypto/SyncEnvelope.java`（跨设备撤回归属这条链路）
   - `MIMServer/sql/e2ee.sql` 末尾的升级补丁
2. **Phase 2（进行中）**：2a、2b 已完成并默认关闭；`SenderCertE2eTest`
   已覆盖服务端契约与真实双棘轮往返，但 **`MessageService` 的接线仍只在
   单元测试层面验证过**，开启前必须人工跑一遍首次联系/TOFU/会话丢失。
   2c 已完成令牌签发/消费、匿名提交入口与客户端接入（默认关闭，`-Dmim.anonymousSend=true`）；
   但**预密钥领取与多端同步仍会关联账号**，所以只能宣称「提交与落库不含发送者」，
   不能宣称完全匿名。下一步：客户端侧拉黑、清理 `idx_ref_*`。见第 11 节。
3. **运维收尾**：KMS SDK 直连（目前只有 CLI 命令桥接）、真实 SMTP、TOFU 人工核对流程。
4. **长期迁移**：客户端 JavaFX 8/Boot 2.7 BOM 暂不升级；服务端保持 Java 17。

### 关键实现点

- `sessionId` 由发起方生成并写入 `SessionState`，接收方在首条桥接消息中学习；
- `sessionIdConfirmed` 为真后，下一条消息才切 sealed；对端是旧客户端时永远停留在桥接模式；
- 收件端按 `sessionId -> (peer, device)` 索引棘轮；找不到会话时不要猜测发送者；
- 发送端历史用本地 `PlaintextStore` 的 `sent|peer|clientMsgId`；棘轮不能解自己发出的密文；
- 服务端历史通过 `fromMe` 和盲引用查询区分收发；
- 客户端为每条逻辑消息生成 32 字节随机 `recallToken`，随副本提交；
  本地用 `PlaintextStore` 加密保存 `token|peer|clientMsgId`，服务端只存 SHA-256；
- 撤回优先走 token：消息有 `recall_token_hash` 就必须提交匹配的原始 token；
  没有哈希的旧消息才回退到发信人引用；
- 设备间同步副本共用原消息的 `recall_token_hash`，一次撤回覆盖所有副本；
- 握手参数（`sender_identity_key` / 临时公钥 / OPK 编号）只在投递前存在：
  身份公钥能与 `t_user_key.public_key` 直接 join 出用户名，所以
  `/message/delivered` 会在标记已投递的同一条 UPDATE 里把它们清空；
  已建立会话的新客户端不再发送这些参数；
- 跨设备撤回：设备声明 `supportsRecallToken` 后，历史同步会把凭证装进
  `SyncEnvelope` 信封，随**已加密**的同步副本送到本账号其它设备；
  服务端仍只看到密文与哈希，旧客户端只收纯文本副本；
- UI/历史同步必须走 `MessageService.peerForMessage`，不能把 HMAC 盲引用当用户名；
- 多端同步副本使用 `syncOriginalFrom/To` 签名还原归属；
- `MIM_SEALED_SENDER_ENABLED=false` 会把新 sealed 请求降级为旧格式，但**已落库 sealed 仍可读**；不要改成直接拒绝，否则已切换的客户端会永久发不出消息；
- 盲引用密钥优先 `MIM_SECURITY_METADATA_KEY`（Base64 32 字节），未配置回退 JWT 私钥；更换它会使旧 sealed 历史/未读/撤回索引失效。

### 运行与验证

```bash
# 服务端 JDK 17；客户端必须带 JavaFX 的 JDK 8
export MIM_SERVER_JAVA_HOME=/path/to/jdk-17
export MIM_CLIENT_JAVA_HOME=/path/to/zulu-fx-8

# 集成测试/本地 MySQL（仓库默认 3306；本地开发常用 13306）
export MIM_TEST_DB_URL="jdbc:mysql://127.0.0.1:13306/mim_test?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true"
export MIM_TEST_DB_USERNAME=root
export MIM_TEST_DB_PASSWORD=

# 全量回归（--e2e 需要另起 MySQL/Redis/服务端）
./verify.sh
./verify.sh --e2e
```

- CI：`gh run list` / `gh run watch <id> --exit-status`；当前 workflow 同时跑服务端 17、客户端 FX8、e2e。
- 临时服务端验证完必须停掉，不要占用 8080。
- 推送使用 **SSH**（`origin` 已是 `git@github.com:June-D/MIM.git`），不要再走 HTTPS。
- 不要提交 `.commandcode/`。

### 当前已知限制

- 盲引用可被持有服务端密钥者关联；服务端提交时仍知道发送者；
- 撤回凭证保存在发送端本地：同账号其它设备只有跑过历史同步、且同步时目标设备
  已声明 `supportsRecallToken` 才拿得到凭证；本机数据被清除后只能走旧版兼容路径；
- 多端 sender 的其它设备不会解密 raw sealed 推送，依赖历史同步；这是现有边界；
- 跨设备历史只补本机留有原文的最近 50 条；
- 首次 TOFU 仍靠人工核对；邮箱找回依赖真实 SMTP。

## 加密方案

采用 **X3DH + Double Ratchet**，**按设备**建立独立会话。服务端对消息内容与信任链都保持「零知识」。

### 多设备模型

每个设备拥有自己独立的身份密钥、预密钥与棘轮状态。给一个用户发消息时：

```
1. 领取该用户所有有效设备的预密钥包（每个设备一个包，各自消耗一个 OPK）
2. 为每个设备各派生一条独立棘轮链，各加密一份密文
3. 一次性提交 N 份密文（共享同一个 clientMsgId）
4. 每台设备只拉取"目标是自己"的那一份并解密
```

因此「用户」与「设备」是两个维度：`t_message` 的每条记录是
`(from_user, from_device_id, client_msg_id, target_device_id)` 唯一的一份密文副本。

设备注销后不再出现在预密钥分发里，新消息也不再发给它。

### 会话初始化（X3DH）

```
DH1 = DH(IK_A, SPK_B)   发起方身份密钥 × 接收方签名预密钥
DH2 = DH(EK_A, IK_B)    发起方临时密钥 × 接收方身份密钥
DH3 = DH(EK_A, SPK_B)   发起方临时密钥 × 接收方签名预密钥
DH4 = DH(EK_A, OPK_B)   发起方临时密钥 × 接收方一次性预密钥（可能省略）
SK  = HKDF(DH1 || DH2 || DH3 || DH4, "MIM/v2/initial", 32)
```

- **SPK（签名预密钥）** 由身份私钥签名，接收方用**已固定的身份公钥**验签。
  这让服务端即使被攻破也无法伪造预密钥包 —— 它没有用户的身份私钥。
- **OPK（一次性预密钥）** 每个只会被一个请求者预留；同一请求者重试会拿回同一把，
  消息**真正使用后**才从服务端删除。预留 10 分钟未使用会自动释放，供其他请求者领取。
- **支持对离线用户发起会话**：只要对方注册设备并上传过预密钥，无需在线。

### 棘轮推进

```
KDF_RK(rk, dhOut) : prk   = HKDF-Extract(salt=rk, ikm=dhOut)
                    newRK = HKDF-Expand(prk, "MIM/v2/root", 32)
                    newCK = HKDF-Expand(prk, "MIM/v2/chain", 32)

KDF_CK(ck)        : mk     = HMAC-SHA256(ck, 0x01)   // 本条消息密钥，用完即弃
                    nextCk = HMAC-SHA256(ck, 0x03)   // 链密钥单向前进
```

消息头 `{dh, pn, n}` 参与 AAD，篡改任意字段解密必然失败。

### TOFU 信任（防中间人）

服务端分发身份公钥，但**服务端不可信**。因此客户端维护本地信任库：

| 状态 | 含义 | 行为 |
|---|---|---|
| `NEW` | 首次见到该用户公钥 | 立即固定，正常发送 |
| `TRUSTED` | 与固定值一致 | 正常发送 |
| `CHANGED` | **与固定值不一致** | **阻断发送**，要求用户线下核对指纹后显式确认 |

与 SSH 的 `known_hosts` 同一思路。界面提供「右键联系人 → 查看安全码」，
展示每台设备的分组指纹，以及一个 **60 位安全码**：它由双方身份公钥按字节序
排序后做 SHA-256 生成，双方对应设备的码应完全一致，适合电话/当面逐位核对。

### 安全性质

| 性质 | 说明 |
|---|---|
| **前向安全性** | 每消息一密钥，由链密钥单向派生后立即销毁；OPK 用完即删 |
| **后向恢复性** | 每次 DH 棘轮混入新 DH 输出，旧状态泄漏无法解新消息 |
| **抗中间人** | SPK 签名 + TOFU 固定身份公钥，服务端无法伪造预密钥包 |
| **抗重放** | 已解密的消息密钥不保留，重放会失败 |
| **乱序容忍** | 跳过的消息密钥会缓存（上限 1000 条） |
| **抗 DoS** | 单条链最多跳过 200 条，超出直接拒绝 |
| **设备隔离** | 每设备的密钥、预密钥、棘轮、离线队列全部独立 |
| **本地保护** | 身份私钥、会话状态、预密钥、信任库全部 PBKDF2+AES-GCM 加密落盘，0600 权限 |

### 已知局限

- **sealed sender Phase 1 不是提交匿名**：已建立会话的 `from_user`/`from_device_id`
  是 HMAC 盲引用，不是明文；但服务端提交时仍从 JWT 知道提交者，持有盲引用密钥者
  仍可关联。严格无发送者索引与提交匿名属于 Phase 1.5 / Phase 2。
- **首条消息仍暴露发送者**：新会话必须先用旧格式完成 X3DH 桥接，确认后才切 sealed。
- **跨设备历史只补本机留有原文的最近 50 条**：旧设备不在线，或这台设备从没解开过的消息，新设备补不回来。
- **发送端多端 sealed 历史依赖同步**：其它设备收到 raw sealed 推送时无法解密，需走已授权设备历史同步。
- **首次信任依赖人工核对**：TOFU 的固有局限，需要用户比对安全码。
- **无群聊**：按需求只做一对一私密交流。

## 目录结构

```
MIMServer/
  sql/chat.sql            基础表（用户、分组、联系人、JWT 密钥）
  sql/e2ee.sql            t_device / t_user_key / t_signed_prekey /
                          t_one_time_prekey / t_message（均按设备维度）
  auth/                   @CurrentUser / @CurrentDevice 参数解析器
  ratelimit/              @RateLimit + RateLimiter（规则）+ RateLimitCounter（计数）
  controller/             Device / Key / PreKey / Message / Chat / User / Login / Register
  service/                KeyService、PreKeyService、MessageService、DeviceService、
                          PresenceService、PushService
  websocket/              SessionRegistry（本机会话）、WebSocketServer（生命周期+心跳）
  config/                 WebMvc、RedisSubscriber、WebSocket、TLS
  src/test/java/          234 项测试（单元 + Mapper/HTTP 真实 MySQL 集成）

MIMClient/
  crypto/                 Hkdf、DhKeyUtil、RatchetService(X3DH+双棘轮)、SessionState、
                          SessionStore、PreKeyStore、IdentityStore、SecretBox、TrustStore
  service/                DeviceIdentity（设备标识）、KeyManager、PreKeyManager、
                          MessageService（按设备收发+棘轮）
  feign/                  Login / Register / Device / Key / PreKey / Message
  websocket/              WSClient（只收推送）
  utils/                  TlsSupport（HTTPS/WSS 信任配置）
  gui/                    Login / Main / Chat / Loading
  src/test/java/          134 项单元测试
  src/test/e2e/           多设备 / sealed / WebSocket 端到端回归（需外部服务，手动运行）
```

## 一键验证

```bash
./verify.sh          # 编译 + 单元测试
./verify.sh --e2e    # 额外跑端到端（需 MySQL/Redis/服务端）
```

脚本会自动定位 JDK（可用 `MIM_SERVER_JAVA_HOME` / `MIM_CLIENT_JAVA_HOME` 覆盖），
并校验客户端 JDK 是否带 JavaFX。

GitHub Actions 配置在 `.github/workflows/ci.yml`，包含 MySQL/Redis service 容器
并会启动服务端跑端到端回归。

## 多实例部署

| 职责 | 实现 |
|---|---|
| 在线状态 | Redis Set `mim:presence:<user>`，记录持有连接的实例，TTL 90s 心跳续期 |
| 跨实例推送 | Redis Pub/Sub 频道 `mim:ws:push`，所有实例订阅，收到后只投递本机会话 |
| 速率限制 | Redis 固定窗口 `mim:ratelimit:<主体>:<路径>`，多实例共享计数 |
| 消息幂等 | DB 唯一键 `(from_user, client_msg_id)`，并发重复插入走 `DuplicateKeyException` 兜底 |
| 离线补偿 | Redis 故障或推送丢失时，客户端通过 `GET /message/undelivered` 补齐 |

`SessionRegistry` 里的 Map **仅代表本 JVM 的连接**，不能当全局视图用。

## 速率限制

| 接口 | 限制 | 维度 |
|---|---|---|
| `POST /login/token` | 10 / 分钟 | IP（防撞库） |
| `POST /register/token` | 5 / 分钟 | IP（防批量注册） |
| `POST /message` | 120 / 分钟 | 用户 |
| `POST /message/recall/{id}` | 30 / 分钟 | 用户 |
| `POST /message/sync` | 60 / 分钟 | 用户 |
| `POST /message/sync/request` | 10 / 分钟 | 用户 |
| `POST /key` | 60 / 分钟 | 用户 |

超限返回 HTTP **429** 且带 `Retry-After`。Redis 故障时 **fail-open**（放行 + 告警）——
限流是保护措施，不该让它阻断正常聊天。

固定窗口的代价是窗口边界可能瞬时放过 2 倍流量，对 IM 场景可接受；
需要更精确可换 Lua 脚本实现滑动窗口。

## 消息撤回

- 只有**发信人本人**能撤回自己的消息（服务端二次校验，非仅前端隐藏）。
- **时间窗口 120 秒**（`message.recall-window-seconds`），避免无限期篡改历史。
- 撤回后服务端**物理清除 `ciphertext` 与 `iv`**，只保留元数据。
- 通过 WebSocket 通知双方，客户端就地替换为「已撤回」占位。
- 界面交互：在消息上右键 → 确认 → 撤回。

## 修改密码

账号菜单 →「修改登录密码」。服务端校验原密码后更新 BCrypt，并自增该用户
**所有设备**的令牌版本：其它设备的旧 JWT 立即失效、WebSocket 连接被关闭，
当前设备拿到新令牌。

本地身份私钥、预密钥、会话状态、信任库、本机设备清单与明文缓存都用登录口令
派生密钥加密，因此客户端会用新口令逐项重新加密。改密前自动把 `~/.mim` 备份到
`~/.mim-backup-<时间戳>`；如果本地重加密中途失败，界面会给出备份路径，
历史数据仍可离线用旧口令恢复。

## 二次验证（TOTP）

账号菜单 →「二次验证」。启用时服务端生成 20 字节随机共享密钥，界面展示
Base32 密钥与 `otpauth://` URI，可直接添加到 Google Authenticator、1Password
等验证器；输入当前 6 位验证码后启用。

启用后登录流程变为「密码 + 6 位验证码」：密码正确但缺少验证码时，服务端返回
专用状态码 `40102`，客户端弹窗要求输入验证码后重试。验证码允许前后各 30 秒的
时钟偏差。**同一个时间步的验证码只能成功使用一次**：用 Redis `SETNX` 原子占用，
TTL 90 秒；Redis 故障时放行，此时防重放暂时失效（不影响口令校验与 IP 限流）。

启用成功时服务端生成 **10 个一次性恢复码**（12 位，只保存 BCrypt 哈希），
客户端只在启用成功时显示一次。丢失验证器时，登录弹窗可直接输入恢复码；
关闭二次验证、**忘记密码时重置密码**也支持恢复码。每个恢复码只能使用一次。
账号菜单里的「重新生成恢复码」需要当前密码 + 6 位验证码或一个旧恢复码，
生成后旧恢复码全部失效；关闭二次验证或邮箱重置会清空全部恢复码。

启用/关闭二次验证都会自增该用户所有设备的令牌版本并关闭 WebSocket：其它设备
需要重新登录，当前设备拿到新令牌。关闭还需要再次输入当前密码和验证码/恢复码。

> TOTP 共享密钥使用 AES-256-GCM 加密存储（AAD 绑定用户名），旧版明文密钥
> 仍可读取并在下次生成时自动转为密文。但若攻击者同时拿到数据库与
> `MIM_EMAIL_KEY`（未配置时回退用的 JWT 私钥），仍可解密——TOTP 的目标是
> 防口令泄露与撞库，而不是防完全的服务端沦陷。

## 邮箱找回密码与用户协议

**注册**时可选择填写邮箱，仅用于找回密码，**仅本人可见**，不会出现在搜索、
联系人等接口里。邮箱使用 AES-256-GCM 加密存储，AAD 绑定用户名；密钥优先取
`MIM_EMAIL_KEY`（Base64 32 字节），未配置时从 JWT 私钥派生。注册**不验证邮箱
真实性**，界面会明确提示：必须填写真实有效地址，否则无法找回密码。

注册前必须勾选同意《用户协议》并在客户端阅读条款；服务端同时校验
`agreedTerms=true` 与协议版本，未同意无法注册。协议包含风险与免责声明：
不保证消息时效性、到达率或不丢失；因消息延迟/丢失、设备损坏、误删、
账号或设备泄露、口令遗忘、邮箱失效等造成的损失由用户自行承担。

**找回密码**：

- `POST /recover/email`：输入用户名；如果该账号填写了邮箱，发送 6 位验证码。
  账号不存在或未填邮箱时静默返回，避免账号枚举。
- `POST /recover/reset`：用户名 + 邮箱验证码 + 新密码。验证码 10 分钟有效、
  最多尝试 5 次、同一账号 60 秒内只能发送一次；服务端只保存 BCrypt 哈希。
- 重置成功后关闭该账号的二次验证、自增所有设备令牌版本并踢掉 WebSocket，
  用户用新密码重新登录。

邮件服务通过环境变量配置：`MIM_SMTP_HOST`、`MIM_SMTP_PORT`（默认 465）、
`MIM_SMTP_USERNAME`、`MIM_SMTP_PASSWORD`、`MIM_MAIL_FROM`、`MIM_SMTP_SSL`。
未配置 SMTP 时找回密码接口会提示“邮件服务未配置”，其它功能不受影响。
SSL 端口强制校验服务器证书主机名；非 SSL 端口要求 STARTTLS，拒绝明文降级。

## 跨设备历史

新设备登录后，会请本账号其他设备把历史补过来。已经在线、并且本机留有原文的设备，会用一条单独的设备间会话把最近 50 条重新加密，交给新设备。服务端只保存这份新密文，读历史时优先返回当前设备解得开的那一份。

旧设备当时不在线的话，等它下次登录会补发。没有任何一台在线设备留过原文的消息，无法补回。

**设备授权**：历史里是明文，不能服务端说哪台设备就发给哪台。每台设备第一次请求同步时，持有原文的设备会弹出确认框，展示请求设备的标识与身份指纹；用户必须在两台设备上核对「本机设备安全码」（账号菜单里可查看）后才能确认。拒绝后本次会话不再重复弹窗。这样即使服务端被攻破或 JWT 被盗，攻击者也无法静默拉走聊天记录。

同步副本还会带**转发设备的来源签名**：签名覆盖原消息的发送者、收信人、发送设备、clientMsgId 与正文。接收方在显示前用转发设备的身份公钥验签，并以签名里的归属为准；服务端即使篡改 `from_user`，也无法把历史内容挂到别人名下。接收新设备同样会展示旧设备指纹，要求人工确认。

## TLS

传输层加密保护的是口令、JWT 与元数据（谁和谁在通信）。没有 TLS 时，
中间人可直接窃取 JWT 冒充身份，E2EE 也就形同虚设。

### 服务端

```bash
# 生成自签证书（仅本地）
keytool -genkeypair -alias mim -keyalg RSA -keysize 2048 \
  -dname "CN=localhost" -validity 365 -storetype PKCS12 \
  -keystore /path/to/mim-tls.p12 -storepass changeit -keypass changeit

export MIM_TLS_ENABLED=true
export MIM_TLS_KEYSTORE=/path/to/mim-tls.p12
export MIM_TLS_KEYSTORE_PASSWORD=changeit
```

启用后监听 `8443`。开启 TLS 后，默认会拒绝 `8080` 明文端口上的业务请求
（`mim.tls.block-plaintext=true`），避免攻击者绕过 TLS 窃取口令与 JWT；
确实需要内网明文调试时再显式关闭。协议固定 TLS 1.2/1.3，实测协商结果为
`TLSv1.3 + AEAD-AES256-GCM-SHA384`。

### 客户端

`feign-client.properties` 里改：

```properties
feign-client.ribbon.listOfServers = mim.example.com:8443
feign-client.ribbon.IsSecure = true
mim.tls.verifyCertificate = true
```

WebSocket 同步改成 `-Dmim.ws.url=wss://mim.example.com/websocket/test`。
自建 CA 时用 `-Dmim.tls.caFile=/path/to/ca.pem` 追加信任。
WebSocket 只在握手时校验 JWT；服务端每分钟关闭一次已过期的连接，
令牌过期后不会继续接收推送。

> `mim.tls.verifyCertificate=false` 只允许本地调试，会打印警告日志。

## 运行

### 依赖

- 服务端 **JDK 17**（无需 JavaFX）
- 客户端 **JDK 8**（需**带 JavaFX**，如 Zulu FX 8；Corretto 8 不含 JavaFX）
- Maven Wrapper（`./mvnw`）固定 **Maven 3.9.9**；也可使用系统 Maven 3.6.3+。
- MySQL 8 / Redis 5+

```bash
curl -L -o zulu8fx.zip https://cdn.azul.com/zulu/bin/zulu8.98.0.111-ca-fx-jdk8.0.512-macosx_aarch64.zip
unzip -q zulu8fx.zip -d ~/jdk
export JAVA_HOME=~/jdk/zulu8.98.0.111-ca-fx-jdk8.0.512-macosx_aarch64/Contents/Home
```

### 建库

```bash
mysql -uroot -e "CREATE DATABASE mim_core CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -uroot mim_core < MIMServer/sql/chat.sql
mysql -uroot mim_core < MIMServer/sql/e2ee.sql
```

从旧版本升级时，`e2ee.sql` 末尾附了设备/消息/OPK 的 `ALTER` 补丁，
`chat.sql` 末尾附了用户表 TOTP/邮箱/协议列的 `ALTER` 补丁（取消注释后执行）。
本轮新增了以下列，从旧库升级时**必须**执行对应 `ALTER`，否则相关查询会因缺列失败：
- `t_device.token_version`：设备令牌版本，登出/注销时自增使旧 JWT 失效；
- `t_message.sync_original_from / sync_original_to / sync_original_device / sync_signature`：
  同步副本的来源签名；
- `t_message.recipient_spk_id`：握手使用的接收方签名预密钥编号；
- `t_register_users.totp_secret / totp_enabled`：TOTP 密钥（AES-GCM 密文）与开关；
  `totp_secret` 必须是 `varchar(255)`，旧库需执行 `MODIFY` 扩容（加密后约 81 字符）；
- `t_register_users.email_cipher / terms_version / agreed_at`：加密邮箱、
  用户协议版本与同意时间；
- `t_one_time_prekey.claimed_by / claimed_at`：OPK 预留请求者与预留时间；
- `t_user_key.supports_padding`：设备是否支持消息长度填充（能力协商）；
- `t_user_key.supports_recall_token`：设备能否在同步副本里接收加密的撤回凭证；
  不声明时只会收到纯文本副本，旧库执行 `e2ee.sql` 末尾的对应 `ALTER`；
- `t_user_key.supports_sender_cert`：设备能否解析首条消息里的发送者证书；
  不声明时继续收明文发送者格式，旧库执行对应的 `ALTER`；
- `t_message.padding`：该条明文是否做了长度填充；
- `t_message.sealed / session_id`：已建立会话后是否隐藏明文发送者，
  以及收件端索引棘轮用的会话 ID；旧库执行 `e2ee.sql` 末尾的 Phase 1 `ALTER`；
- `t_message.from_ref / from_device_ref`：sealed 消息的 HMAC 盲引用，
  `from_user` / `from_device_id` 对 sealed 消息为 NULL；
- `t_message.recall_token_hash`：一次性撤回凭证的 SHA-256；有值时撤回必须
  提交原始 token，旧库执行 Phase 1.5 的 `ALTER` 与回填；
- `t_message.conversation_tag`：客户端生成的会话标签（不透明），
  按 `(tag, to_user, id)` 建索引，供 2c 阶段替代盲引用查历史；
  旧库执行对应的 `ALTER`；
- 唯一索引 `uk_client_target` 已改为 `(client_msg_id, target_device_id)`；
  迁移前先跑注释里的重复检查，避免旧数据冲突导致 `ADD UNIQUE` 失败；
- `t_rsa_key.key_value`：启用 `MIM_MASTER_KEY` 前扩到 `varchar(4096)`，
  旧库执行 `MODIFY`（加密后的 RSA 私钥约 2.2KB）。

旧库中由早期启动 DDL 创建的 `t_conversation_clear` / `t_friend_request`
可能继承了 MySQL 8 默认的 `utf8mb4_0900_ai_ci`，与消息表的
`utf8mb4_general_ci` 比较会报 1267。升级时执行：

```sql
ALTER TABLE t_conversation_clear CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE t_friend_request CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

### 启动服务端

```bash
cd MIMServer
export JAVA_HOME=/path/to/jdk-17
export MIM_DB_URL="jdbc:mysql://127.0.0.1:3306/mim_core?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8"
export MIM_DB_USERNAME=root
export MIM_DB_PASSWORD=
export MIM_REDIS_HOST=127.0.0.1
export MIM_REDIS_PORT=6379
export MIM_REDIS_USERNAME=
export MIM_REDIS_PASSWORD=
export MIM_REDIS_SSL=false
export MIM_REDIS_REQUIRE_SECURE=false
export MIM_MASTER_KEY=
export MIM_SEALED_SENDER_ENABLED=true
# Phase 2c：盲签名令牌与匿名提交，默认关闭（两者要一起开）。
# export MIM_SENDER_TOKEN_ENABLED=false
# export MIM_ANONYMOUS_SEND_ENABLED=false
# Phase 2a：发送者证书，默认关闭。开启后 /cert/* 才可用。
# export MIM_SENDER_CERT_ENABLED=false
# 可选：独立的消息元数据盲引用密钥（Base64 32 字节）。
# 未配置时由 JWT 私钥派生；轮换 JWT 私钥会使旧 sealed 历史索引失效。
# export MIM_SECURITY_METADATA_KEY=
../mvnw spring-boot:run
```

> 服务端需要 JDK 17；Maven 已由 `mvnw` 固定为 3.9.9。客户端仍需带 JavaFX 的 JDK 8。
> 两个 JDK 可以并存，分别通过 `JAVA_HOME` / `MIM_CLIENT_JAVA_HOME` 指定。

> `MIM_MASTER_KEY` 是 JWT RSA 私钥的静态加密密钥（Base64 32 字节）。
> 配置后请务必备份；丢失或错误会导致服务端拒绝启动，且不会静默换钥。
> 启用前先把 `t_rsa_key.key_value` 扩到 `varchar(4096)`。
> 也可以把密钥放到文件里，用 `MIM_MASTER_KEY_FILE` / `MIM_EMAIL_KEY_FILE`
> 指向 Docker/K8s Secret 挂载路径；环境变量优先。
> 轮换时把新密钥放主配置、旧密钥放 `MIM_MASTER_KEY_PREVIOUS` /
> `MIM_EMAIL_KEY_PREVIOUS`，启动时会自动重加密，详见下方「密钥轮换与备份」。

> Redis 必须设置密码并只监听本机或内网。非本机 Redis 还应启用 TLS
> （`MIM_REDIS_SSL=true`）；设置 `MIM_REDIS_REQUIRE_SECURE=true` 后，
> 非本机地址缺密码或 TLS 会直接拒绝启动。
> Pub/Sub 推送频道本身不带签名，Redis 被攻破等同于能伪造推送信封；
> 不要把 6379 暴露到公网。

### Sealed sender Phase 1.5

已建立会话的消息不再写入明文 `from_user` / `from_device_id`：

- `sealed=1`、`session_id` 标识会话，`from_user` / `from_device_id` 写 NULL；
- `from_ref` / `from_device_ref` 保存 HMAC-SHA256 盲引用（64 位十六进制），
  历史、未读、撤回、清除和多端同步按盲引用查询；
- 幂等唯一键是 `(client_msg_id, target_device_id)`，发送者不参与；
- 客户端发送时为每条逻辑消息生成一次性 `recallToken`，服务端只存 SHA-256；
  撤回必须提交匹配的 token；没有哈希的旧消息回退到发信人引用授权；
- 首条会话消息和新旧客户端桥接消息仍走旧格式（服务端可见发送者）；
- 服务端在提交时仍从 JWT 知道提交者，**这不是提交时匿名**；
  完全提交匿名属于 Phase 2 的匿名令牌/发送者证书。

关闭 `MIM_SEALED_SENDER_ENABLED` 会把 sealed 请求降级为旧格式存储（不再隐藏
发送者，但不会中断已有会话）；旧客户端不受影响。
盲引用密钥 `MIM_SECURITY_METADATA_KEY` 必须与数据库一起备份；更换它会让
旧 sealed 消息的历史/未读/撤回索引失效。设计取舍详见
[docs/sealed-sender-design.md](docs/sealed-sender-design.md) 第 9、10 节。

**从 Phase 1 升级**：必须执行 `MIMServer/sql/e2ee.sql` 末尾的 Phase 1.5 补丁。
顺序不能颠倒：加列 → 回填 `from_ref` → 改唯一索引 → 置 NULL。
只加列不回填会让旧 sealed 历史在迁移后查不到；先跑注释里的重复检查，
确认没有「同一 clientMsgId + 目标设备」的冲突数据再改唯一索引。
回滚到 Phase 1 代码前，先保留 `from_ref` / `from_device_ref` 的副本，
否则旧代码读不到盲引用。

## 密钥轮换与备份

### 邮箱 / TOTP 密钥

密钥来源优先级：`MIM_EMAIL_KEY` > `MIM_EMAIL_KEY_FILE` > `MIM_EMAIL_KEY_COMMAND`。
外部命令适合 Vault/AWS/GCP Secret Manager；启动读取时执行一次（15 秒超时），
stdout 必须是 Base64 32 字节，命令输出不会写入日志：

```bash
export MIM_EMAIL_KEY_COMMAND='vault kv get -field=key secret/mim/email'
export MIM_EMAIL_KEY_PREVIOUS_COMMAND='vault kv get -field=key secret/mim/email-old'
```

1. 生成新的 32 字节 Base64 密钥，设置：
   `MIM_EMAIL_KEY=<新> MIM_EMAIL_KEY_PREVIOUS=<旧>`（也支持 `*_FILE` / `*_COMMAND`）。
2. 重启服务端。`EmailKeyRotation` 会用当前密钥解密旧数据并重加密，
   日志出现「邮箱/TOTP 密钥轮换完成」。旧密钥仍可读，但新写入全部用新钥。
3. 确认账号的 2FA 登录正常后，移除 `MIM_EMAIL_KEY_PREVIOUS`，再次重启。

如果旧数据来自未配置 `MIM_EMAIL_KEY` 时的 JWT 私钥派生密钥，
直接设置新的 `MIM_EMAIL_KEY` 即可启动：轮换器会把旧派生密钥加密的数据
迁移到显式密钥，无需配置 `_PREVIOUS`。

### JWT 主密钥

1. 设置 `MIM_MASTER_KEY=<新> MIM_MASTER_KEY_PREVIOUS=<旧>`，重启。
2. `AuthServerRunner` 会先用旧钥解出 JWT RSA 私钥，再用新钥重加密
   `t_rsa_key`；**RSA 签名密钥本身不变**，已签发 JWT 不受影响。
3. 确认登录正常后移除 `MIM_MASTER_KEY_PREVIOUS`，再次重启。

主密钥同样支持 `MIM_MASTER_KEY_COMMAND` / `MIM_MASTER_KEY_PREVIOUS_COMMAND`。

### 备份

- 必须同时备份数据库与 `MIM_MASTER_KEY` / `MIM_EMAIL_KEY`；密钥不在数据库里。
- 丢失主密钥会导致服务端拒绝启动；丢失邮箱密钥会导致 TOTP/邮箱密文无法解密。
- 建议定期做恢复演练：在隔离环境用备份的数据库 + 密钥启动，确认能登录、
  能解密 TOTP/邮箱，再销毁演练环境。
- 一次只轮换一类密钥；完成并确认后再轮换另一类。保留 `_PREVIOUS` 期间不要
  再轮换到第三把密钥。

### 启动客户端

```bash
cd MIMClient
../mvnw compile
../mvnw exec:java -Dexec.mainClass=com.junesoon.mim.MIMClientStarter
```

首次登录生成身份密钥对，加密存到 `~/.mim/identity/`；会话棘轮状态存到 `~/.mim/sessions/`。

### 自测

```bash
# 一键（推荐）
./verify.sh

# 或分端执行（在各自目录）
cd MIMServer && ../mvnw test      # 234 项（含真实 MySQL 集成）
cd MIMClient && ../mvnw test      # 134 项：HKDF/SecretBox、X3DH+双棘轮、TOFU、加密落盘
```

**集成测试需要真实 MySQL**（不用 H2：Mapper 依赖 `ON DUPLICATE KEY UPDATE`、
`INSERT IGNORE`、`SELECT ... FOR UPDATE`、分组去重子查询，H2 兼容模式覆盖不全）：

```bash
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS mim_test CHARACTER SET utf8mb4;"
export MIM_TEST_DB_URL="jdbc:mysql://127.0.0.1:3306/mim_test?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true"
../mvnw test
```

数据库不可用时集成测试会**跳过**（本地友好）；CI 里设置
`MIM_TEST_DB_REQUIRED=true` 则**明确失败**，避免「0 个测试 + BUILD SUCCESS」的假绿。

端到端回归需要先起 MySQL/Redis/服务端，然后：

```bash
./verify.sh --e2e
```

覆盖内容：

> `SenderCertE2eTest` / `SenderTokenE2eTest` 需要服务端打开对应开关
> （`MIM_SENDER_CERT_ENABLED` / `MIM_SENDER_TOKEN_ENABLED` +
> `MIM_ANONYMOUS_SEND_ENABLED`）；CI 的 e2e job 已默认打开。
> 未启用时脚本打印 SKIP 并以 0 退出，不会伪装成通过。
> 客户端匿名发送需要额外加 `-Dmim.anonymousSend=true`。

| 测试/脚本 | 覆盖点 |
|---|---|
| `MapperIntegrationTest`（45 项） | XML 与 schema 匹配、upsert 语义、`INSERT IGNORE`、`FOR UPDATE` 领取、去重分页、撤回级联、sealed 盲引用与 token 撤回 |
| `HttpApiIntegrationTest`（20 项） | 鉴权 401、限流 429、`@CurrentUser`/`@CurrentDevice` 解析、伪造发信人、多设备扇出 |
| `MultiDeviceE2eTest`（30 项） | 多设备注册、按设备分发、各自解密、设备隔离、按 clientMsgId 撤回、sealed 往返/历史/撤回、缺 token 撤回被拒、注销后停止分发 |
| `SenderTokenE2eTest`（7 项） | 致盲签名跨模块验证、匿名提交无发送者、令牌重放被拒、收信端按标签查历史。需 `MIM_SENDER_TOKEN_ENABLED=true` 与 `MIM_ANONYMOUS_SEND_ENABLED=true`，关闭时明确 SKIP |
| `SenderCertE2eTest`（18 项） | 证书签发与独立验签、首条 sealed 不落 from_user、收信端着陆归属、投递后擦除握手参数、首条 sealed 在收信端历史可查、会话标签往返、按标签查历史/未读/已读/清除。需服务端 `MIM_SENDER_CERT_ENABLED=true`，关闭时明确 SKIP |
| `WsDevicePushE2eTest`（13 项） | WS 按设备推送：两台设备各收自己那份、多端同步副本、密文互不相同、都能解密 |

## HTTP 接口

所有需要身份的接口都从 `Authorization: Bearer <JWT>` 取用户。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/register/token` | 注册（IP 限流 5/分） |
| POST | `/login/token` | 登录，返回 JWT（IP 限流 10/分） |
| GET | `/user/info` | 当前用户信息 |
| POST | `/user/heartbeat` | 在线心跳续期 |
| POST | `/user/password` | 修改登录密码（限流 5/分，成功后旧令牌全部失效） |
| GET | `/user/2fa/status` | 查询二次验证是否启用 |
| POST | `/user/2fa/setup` | 生成 TOTP 共享密钥（返回密钥与 otpauth URI） |
| POST | `/user/2fa/enable` | 校验验证码并启用，返回新令牌与 10 个恢复码 |
| POST | `/user/2fa/disable` | 当前密码 + 6 位验证码或恢复码，关闭二次验证 |
| POST | `/user/2fa/recovery-codes` | 当前密码 + 验证码/恢复码，重新生成 10 个恢复码 |
| POST | `/recover/email` | 发送邮箱找回密码验证码（IP 限流 5/分） |
| POST | `/recover/reset` | 邮箱验证码重置密码（IP 限流 10/分） |
| POST | `/recover/code` | 一次性恢复码重置密码（关闭 2FA，IP 限流 10/分） |
| GET | `/chat/contacts` | 联系人（分组 + 在线状态） |
| POST | `/device` | 注册/刷新本设备 |
| GET | `/device` | 列出本人所有有效设备 |
| DELETE | `/device/{deviceId}` | 注销设备（停止接收消息） |
| POST | `/key` | 上传/更新本设备公钥（新增或换钥需当前密码，同指纹幂等） |
| GET | `/key/{username}` | 查询他人**所有设备**公钥（可加 `?deviceId=` 限定） |
| GET | `/key/self` | 查询本设备公钥状态 |
| POST | `/prekey/signed` | 上传/轮换本设备签名预密钥 |
| POST | `/prekey/one-time` | 批量上传本设备一次性预密钥（上限 200） |
| GET | `/prekey/status` | 本设备剩余一次性预密钥数量 |
| GET | `/prekey/{username}` | 拉取对方**每台设备**的预密钥包 |
| POST | `/message` | 发送密文**数组**（每台目标设备一份，`clientMsgId` 幂等） |
| GET | `/message/history/{peer}` | 历史消息（按 clientMsgId 去重） |
| GET | `/message/undelivered` | 本设备未投递消息 |
| POST | `/message/delivered?beforeId=` | 本设备批量确认已投递 |
| POST | `/message/read` | 标记已读并发回执 |
| GET | `/message/unread/{peer}` | 未读数（按逻辑消息计数） |
| POST | `/message/recall/{clientMsgId}` | 撤回（作用于所有设备副本，限 120s） |
| WS | `/websocket/*` | 下行推送（握手需 `Authorization` 头） |

所有需要身份的接口都从 `Authorization: Bearer <JWT>` 取用户；
涉及密钥与消息的接口还需 `X-Device-Id` 头指定设备（缺省降级为 `default`）。

## WebSocket 通知类型

| 值 | 含义 | 投递范围 |
|---|---|---|
| 100000 | 连接成功 | 仅本设备 |
| 100001 / 100002 | 联系人上线 / 离线 | 联系人的**所有设备**（在线状态是用户级信息） |
| 100010 | 新消息（载荷为密文） | 收信人的**目标设备**；发信人的**其它设备**（多端同步，排除发起设备） |
| 100012 | 已读回执 | 对方**所有设备** |
| 100014 | 消息被撤回 | 双方**所有设备** |
| 100021 | 心跳响应 | 仅本设备 |
| 100023 | 登录安全提醒（设备标识+时间） | 同账号**其他在线设备**，排除本次登录设备 |

按设备路由是必需的：消息为某个设备加密，只有它能解密。
若按用户广播，同账号其它设备会收到解不开的密文。客户端还会再做一次
`targetDeviceId` 校验作为双保险。

上行只接受心跳。发消息一律走 HTTP，以便获得明确的失败回执与幂等保证。

## 相对原始雏形的改动

**第一轮**

1. 修复服务端无法编译（引用了不存在的 `EncryptUtil` 与 `KeyConfiguration.getMsgSecret()`）。
2. 客户端移除 `com.sun.*` 内部类 Base64（Java 9+ 不可用）。
3. 补齐 `t_user_key` / `t_message` 建表语句与索引。
4. 暴露公钥与消息接口（原先实现完整但没有任何 Controller）。
5. 客户端实现真正的加密，替换掉**硬编码全局密钥 + AES/ECB + 直连 Redis** 的假加密。
6. JWT 从 URL 路径迁到 Authorization 头。
7. WebSocket 不再信任报文里的 `fromUsername`。
8. 在线状态与推送改为多实例可用（Redis Set + Pub/Sub）。
9. 配置文件里的明文数据库密码改为环境变量。

**第二轮**

10. 加密方案升级为 **Double Ratchet**，补齐前向安全性与后向恢复性。
11. 会话状态加密落盘 + 原子写，崩溃不会导致棘轮不同步。
12. 新增 **TLS**（HTTPS 8443 + WSS），限制 TLS 1.2/1.3。
13. 新增 **消息撤回**（限发信人、120s 窗口、服务端物理清除密文）。
14. 新增 **速率限制**（Redis 固定窗口，覆盖登录/注册/发消息/撤回/上传公钥）。
15. 未认证返回 401、限流返回 429，便于客户端与网关做退避重试。

**第三轮**

16. 新增 **预密钥包**（签名预密钥 + 一次性预密钥），**支持对离线用户发起会话**。
17. X3DH 补齐为完整四段 DH（DH1–DH4），初始消息具备前向安全性。
18. 新增 **TOFU 信任库**：身份公钥固定与变更阻断，抵御服务端目录被篡改的中间人。
19. 签名预密钥由身份私钥签名，**接收方验签**，服务端不参与信任链。
20. 一次性预密钥「领取即删除」，`SELECT ... FOR UPDATE` + `DELETE` 同事务保证一次性语义。

**第四轮（多设备 + 工程化）**

21. 新增 **多设备**：每台设备独立身份密钥、预密钥、棘轮链与离线队列。
22. 发消息时按设备扇出：一次提交 N 份密文，每台设备只拉自己那份。
23. 撤回改为按 `clientMsgId`，一次作用于所有设备副本。
24. 新增设备管理接口（注册/列表/注销），注销后停止接收新消息。
25. 限流逻辑拆分：`RateLimiter`（规则，可单测）+ `RateLimitCounter`（Redis 计数）。
26. 服务端测试从 13 项扩到 **81 项**：撤回规则、消息校验、密钥目录、
    预密钥一次性语义、多设备分发、限流规则、输入校验、**WS 设备级路由**。
27. 新增 `verify.sh` 一键验证与 GitHub Actions（含 MySQL/Redis service）。

**第五轮（WS 设备级路由）**

28. WebSocket 推送改为**按设备路由**：`SessionRegistry` 索引键从 username
    改为 `username + deviceId`。
29. 新增三种投递范围：`pushToDevice`（精确）、`pushToUser`（全设备）、
    `pushToUserExcept`（多端同步排除发起方）。
30. 推送信封扩展 `device` / `exclude` 字段，Redis Pub/Sub 跨实例仍按同一语义投递。
31. 握手改为透传 `X-Device-Id`，会话属性记录设备标识。
32. 客户端 WS 连接带设备头，并对收到的密文再做一次 `targetDeviceId` 校验（双保险）。
33. 端到端新增 `WsDevicePushE2eTest`（13 项），实测两台设备各收自己那份、
    密文互不相同、都能解密。

**第六轮（集成测试）**

34. 新增 `MapperIntegrationTest`（25 项）：真实 MySQL 上验证 MyBatis XML ——
    `ON DUPLICATE KEY UPDATE`、`INSERT IGNORE`、`FOR UPDATE` 领取、
    去重分页、撤回级联、TEXT 字段不截断、唯一索引生效。
35. 新增 `HttpApiIntegrationTest`（17 项）：`@SpringBootTest` + MockMvc 验证
    鉴权 401、限流 429、参数解析器、伪造发信人、多设备扇出。
36. 集成测试基类：Spring 上下文启动前建表（`AuthServerRunner` 依赖 `t_rsa_key`），
    数据库不可用时跳过；CI 设 `MIM_TEST_DB_REQUIRED=true` 则明确失败，杜绝假绿。
37. CI 拆成三个 job：服务端（含 MySQL/Redis service + 集成测试）、客户端、端到端。
38. TLS 端口改为可配置（`mim.tls.port`），原先写死 8443 会与同机实例冲突。
39. 测试配置强制关闭 TLS：外部环境变量（如残留的 `MIM_TLS_ENABLED=true`）
    不会再污染测试，避免抢 8443 或被证书缺失搞挂。
40. `verify.sh --e2e` 增加前置检查：服务端未就绪时明确报错，
    而不是抛一堆 Connection refused；每个端到端脚本前清空限流计数，
    避免连续注册撞上 5 次/分钟。清空用按行 `redis-cli del`，不依赖 GNU `xargs -r`。
41. 跨设备历史：旧设备把本机已解开的最近 50 条，用设备间会话重新加密给新设备。
    服务端按请求设备返回解得开的那份副本。
42. 集成测试的 Mapper 与 HTTP 类各自使用 `mim_test_mapper`、`mim_test_httpapi`，
    并行时不会互相 `truncate`。

**第七轮（安全加固）**

43. TOFU 信任改为**按设备固定**：首次接触一次性固定该用户全部设备；已固定用户
    新增设备必须人工核对指纹，不再静默信任。修复了第二台设备必然被判成
    `CHANGED`、导致多设备实际不可用的问题。
44. 预密钥**先验签、后落信任库**，避免伪造包把固定值污染成攻击者公钥。
45. 历史同步增加**本机设备授权**：只向用户确认过的设备转发明文。旧设备会弹窗
    展示请求设备的标识与指纹，用户核对「本机设备安全码」后才确认；服务端
    无法单方面触发外发。
46. JWT 绑定登录设备并写入**设备令牌版本**；登出/注销时自增版本，旧令牌立即失效；
    绑定的令牌只能登记自己这台设备，盗号者无法静默新增设备。
47. 发信设备必须是调用者已注册且未注销的设备；`deviceId` 限制字符集与长度，
    修复伪造 `fromDeviceId` 造成的收信端会话/磁盘/CPU 放大。
48. IP 限流默认不再信任 `X-Forwarded-For`；限流计数改用**路由模板**，
    修复换 URL 参数即可重置额度的绕过；预密钥领取增加按目标用户的限流。
49. 新增**请求体大小上限**（登录/注册 16KB、消息 8MB、同步 20MB），
    未声明 `Content-Length` 的 chunked 请求也做流式计数，防止未认证 OOM。
50. 开启 TLS 后默认拒绝明文端口上的业务请求；客户端 Feign 日志从 `FULL`
    改为 `NONE`，避免把 Authorization 与登录请求体写进本地日志。
51. 本地明文缓存键加入发送者与发送设备，修复不同发送者复用 `clientMsgId`
    导致的明文串用；本地明文/同步记录文件名改用 SHA-256，修复中文用户名碰撞。
52. 同步副本增加**转发设备来源签名**，接收方验签后以签名里的原发送者/收信人/
    发送设备为准；首次同步与首次接收同步都要求人工核对设备指纹。
53. 签名预密钥**每 7 天自动轮换**，本地保留最近 3 把；握手消息携带
    `recipientSpkId`，轮换不会打断在途首条消息。
54. WebSocket 增加单用户/单设备连接上限与心跳最小间隔，心跳过快的连接直接断开，
    避免用长连接刷 Redis/MySQL。
55. 密码最小长度改为按 Base64 解码后的**原始口令长度**校验；预密钥领取增加
    「请求者 + 目标」维度的限流，进一步抬高 OPK 耗尽攻击成本。
56. 新增**修改登录密码**：服务端更新 BCrypt、自增该用户所有设备令牌版本并关闭
    本机 WS 连接；客户端用新口令重新加密身份、预密钥、会话、信任库、设备清单
    与明文缓存，改密前自动备份 `~/.mim`。
57. 新增启动安全自检：Redis 未设密码、TLS 未启用、TLS 下仍放行明文端口、
    信任转发头等风险会在启动日志中明确 WARN。
58. 会话文件名分隔符从 `__` 改为 `~`：用户名/设备名包含 `__` 时不再解析歧义；
    旧文件仍可读取，下一次保存自动迁移到新命名。
59. 客户端时间戳限制在「过去 1 年 ~ 未来 5 分钟」内，防止发送方在界面上
    任意倒填或穿越消息时间。
60. 新增 **TOTP 二次验证**（SHA1 / 6 位 / 30 秒）：登录在密码通过后校验验证码，
    缺少验证码时返回专用状态码 `40102` 供客户端弹窗；启用/关闭都会使该用户
    所有设备的旧令牌失效。
61. 启用/关闭二次验证会关闭本机 WebSocket 并重新签发当前设备令牌，客户端
    自动重连。
62. 上传/更换**身份公钥必须验证当前登录口令**：同一指纹重复上传保持幂等，
    新增或换钥则要求口令证明，避免仅凭被盗 JWT 就能把受害者设备的身份公钥
    替换成攻击者的。
63. TOTP 验证码增加**防重放**：同一个 30 秒时间步只能成功使用一次
    （Redis `SETNX` 原子占用，TTL 90 秒）；Redis 故障时 fail-open。
64. 注册支持可选邮箱（仅本人可见，AES-GCM 加密存储），并强制勾选同意
    **用户协议**（含消息时效性/丢失/账号泄露等免责声明）；服务端校验
    `agreedTerms` 与协议版本。
65. 新增**邮箱验证码找回密码**：6 位验证码 10 分钟有效、最多 5 次尝试、
    60 秒发送间隔；重置成功后关闭二次验证并让所有旧令牌失效。
66. 一次性预密钥从「领取即删」改为**按请求者预留、使用后删除**：同一请求者
    重试复用同一把，10 分钟未使用自动释放；只有发信设备本人预留的 OPK
    才能在其消息落库时删除，避免被盗 JWT 随意清空他人预密钥。
67. TOTP 共享密钥改为 **AES-256-GCM 加密存储**（AAD 绑定用户名与字段类型），
    旧版明文自动兼容；邮箱与 TOTP 的密文不能互相搬运。
68. WebSocket 只在握手时校验 JWT 的问题已修复：服务端每分钟关闭 JWT 已过期的
    连接并移出注册表；同时启用 `@EnableScheduling`，密钥刷新任务才会正常执行。
69. SMTP 启用 SSL 时强制校验服务器证书主机名，非 SSL 端口要求 STARTTLS；
    撤回消息的 DOM 选择器只接受合法消息 ID 字符，避免推送内容注入。
70. 邮箱找回密码的验证码改为 Redis **原子比较并删除**：并发请求只有一个能
    消费成功，避免同一个验证码同时重置两次密码。
71. 客户端本地敏感文件权限跨平台化：POSIX 上设 `0600`，Windows 上改为
    只允许当前用户访问的 ACL，避免 Windows 忽略 POSIX 权限导致私钥/会话裸奔。
72. 登录成功时向同账号**其他在线设备**推送登录安全提醒（设备标识+时间），
    客户端弹窗提示：如非本人操作，立即修改密码并在设备管理中注销该设备。
73. 本地 KDF 从 PBKDF2 **12 万次提升到 60 万次**：身份密钥、会话、预密钥、
    明文缓存、信任库文件新增 `iterations` 字段；旧文件按 12 万次读取，
    身份密钥解锁成功时自动升级，其它文件下次保存自动升级。
74. 新增 `MIM_AUTH_REQUIRE_DEVICE_BINDING` 开关（默认 false）：客户端全部升级后
    设为 true，服务端与 WebSocket 会拒绝未绑定设备的旧 JWT，强制重新登录；
    启动自检在未开启时也会提示。未配置 `MIM_EMAIL_KEY` 时同样会在启动日志告警。
75. 服务端依赖升级：Spring Boot **2.3.7 → 2.7.18**，MyBatis starter
    **2.1.4 → 2.3.1**，Druid starter **1.1.14 → 1.2.20**；Tomcat、Jackson、
    Spring Security 等由 BOM 一并更新。无数据库变更，客户端不受影响。
76. 修复 TOTP 密文列长度：`totp_secret` 从 `varchar(64)` 扩到 `varchar(255)`，
    否则加密后的密钥（约 81 字符）存不下；旧库需执行 `MODIFY`。
77. 修复启动 DDL 的 collation：`t_conversation_clear` / `t_friend_request`
    显式使用 `utf8mb4_general_ci`，避免 MySQL 8 下与消息表比较报 1267；
    旧库需执行 `CONVERT TO` 迁移。
78. 修正多设备 e2e 的身份公钥上传：口令改为 Base64，与登录/注册接口一致。
    本地完整回归通过：服务端 127 项、客户端 99 项、e2e 14+13 项。
79. JWT 库从 `jjwt 0.9.1` 升级到 **0.11.5**（`jjwt-api` + `jjwt-impl` +
    `jjwt-jackson`），解析改用 `Jwts.parserBuilder()`；登录、普通接口与
    WebSocket 握手均已通过 e2e。
80. Redis 增加 ACL 用户名、TLS 与强制安全检查：支持 `MIM_REDIS_USERNAME`、
    `MIM_REDIS_SSL`；`MIM_REDIS_REQUIRE_SECURE=true` 时，非本机 Redis
    缺密码或 TLS 会拒绝启动。
81. JWT RSA 私钥支持 `MIM_MASTER_KEY` 的 **AES-GCM 静态加密**：旧库明文
    密钥在启动时无感迁移为密文；主密钥缺失或错误时拒绝启动，绝不静默换钥。
    启用前需把 `t_rsa_key.key_value` 扩到 `varchar(4096)`。
82. `MIM_MASTER_KEY` / `MIM_EMAIL_KEY` 支持从文件读取
    （`MIM_MASTER_KEY_FILE` / `MIM_EMAIL_KEY_FILE`），便于 Docker/K8s
    Secret 或 Vault 挂载；环境变量优先。
83. 服务端迁移到 **Spring Boot 3.3.5 + Java 17**：`javax` → `jakarta`、
    Tomcat 10.1、Druid Boot3 starter、MyBatis 3.0.3、MySQL Connector/J；
    编译开启 `-parameters`（Spring 6 解析未显式命名的路径参数所需）。
    客户端仍为 JavaFX 8，两个 JDK 并存。
84. 启用二次验证时生成 **10 个一次性恢复码**（只存 BCrypt 哈希，单次使用）：
    登录可用恢复码替代 TOTP，关闭二次验证也支持「密码 + 恢复码」；
    关闭 2FA 或邮箱重置会清空全部恢复码。旧库启动时自动建表，无需手工迁移。
85. 消息长度填充（能力协商）：设备上传身份公钥时声明 `supportsPadding`，
    只有双方都支持才在加密前补到 **256 字符固定桶**，消息带 `padding` 标志；
    旧客户端能力为 false，永远收不到填充消息；未填充消息原样展示。
    服务端仍可见粗粒度长度与时间，属于部分缓解。
86. 恢复码闭环：账号菜单可重新生成恢复码（当前密码 + 验证码/旧恢复码）；
    忘记密码页支持用恢复码直接重置密码，成功后关闭 2FA、清空恢复码并让所有
    旧令牌失效。这样邮箱不可用、验证器丢失时仍能恢复账号。
87. 密钥轮换与备份：`MIM_MASTER_KEY` / `MIM_EMAIL_KEY` 支持
    `_PREVIOUS` 双读；启动时自动把旧 JWT 密钥和旧邮箱/TOTP 密文重加密为新钥，
    并附轮换步骤与备份/恢复演练清单。主密钥轮换不改变 RSA 签名密钥。
88. 密钥支持从外部命令读取（`MIM_*_KEY_COMMAND`），stdout 为 Base64 32 字节，
    可直接对接 Vault/AWS/GCP CLI；优先级为内联 > 文件 > 命令，命令输出不写日志。
89. 联系人安全码增加 **60 位数字对照码**：双方身份公钥排序后哈希，双方对应
    设备的码应完全一致；与分组指纹一起用于首次 TOFU 的线下核对。
90. Spring Boot **3.3.5 → 3.5.16**（Java 17、Jakarta 体系不变）；本地真实
    MySQL 下 186 项测试通过，CI 继续覆盖单元、集成与 e2e。
91. 合并次版本/补丁依赖：MyBatis 3.0.5、Druid 1.2.28；客户端 Lombok 1.18.48、
    slf4j 2.0.20、ribbon 2.7.18、Java-WebSocket 1.6.0、Feign 13.18。
92. 引入 **Maven Wrapper 3.9.9**（`mvnw` / `.mvn`），`verify.sh` 与 CI 改用
    `mvnw`；同时启用 compiler 3.16.0 与 surefire 3.6.1，消除本机 Maven 版本差异。
93. 客户端 dependencyManagement/打包插件从 Spring Boot 2.3.7 升到 **2.7.18**
    （客户端仍是 JavaFX 8，不引入 Boot 运行时）；依赖版本与 CVE 面同步收敛，
    107 项测试与 e2e 通过。
94. 落地 **sealed sender Phase 1（盲引用兼容方案）**：已建立会话的
    `from_user`/`from_device_id` 改为 HMAC 盲引用，新增 `sealed`/`session_id`；
    客户端通过 `sessionId + sessionIdConfirmed` 桥接后切换，发送端历史使用本地
    明文缓存；服务端历史/未读/撤回/清除/同步按盲引用继续工作。
    e2e 已覆盖 sealed 往返、历史与撤回。Phase 2 的提交匿名仍未实现，
    设计取舍见 `docs/sealed-sender-design.md` 第 9 节。
95. 落地 **sealed sender Phase 1.5**：sealed 消息的 `from_user`/`from_device_id`
    真正写 NULL，盲引用迁移到独立列 `from_ref`/`from_device_ref`；唯一索引改为
    `(client_msg_id, target_device_id)`；撤回改为一次性 `recallToken`，服务端只存
    SHA-256，旧消息回退到发信人引用授权。历史、未读、清除、同步新增按盲引用查询，
    旧格式继续兼容。`e2ee.sql` 提供升级 `ALTER` + 回填补丁（已在真实 MySQL 旧库
    上验证）。服务端 205 项、客户端 107 项、e2e 28 + 13 项通过。
    设计说明见第 10 节；提交匿名仍属于 Phase 2。
96. 补齐 **跨设备撤回**：设备在上传公钥时声明 `supportsRecallToken`，服务端存
    `t_user_key.supports_recall_token` 并在预密钥包里回传；历史同步据此把一次性
    撤回凭证装进 `SyncEnvelope` 信封，随已加密的同步副本送到本账号其它设备，
    接收端解密后收进本地加密存储。服务端仍只看到密文与哈希，旧客户端只收纯文本
    副本。服务端 210 项、客户端 112 项、e2e 29 + 13 项通过。
97. **sealed 元数据收紧**：`sender_identity_key` 可与 `t_user_key.public_key`
    join 出用户名，因此握手参数改为「投递即擦除」：`/message/delivered`
    在标记已投递的同一条 UPDATE 里清空身份公钥/临时公钥/OPK 编号。
    已建立会话的新客户端不再携带这些参数。
98. 落地 **Phase 2a 发送者证书**（默认关闭）：独立 RSA 密钥
    （`t_rsa_key` 中的 `MIM:AUTH:SENDER-CERT:*`，主密钥加密，与 JWT 密钥分离）
    签发 `{username, deviceId, identityKey, issuedAt, expiresAt, keyId}`，
    `GET /cert/key` 分发验签公钥、`POST /cert/sender` 按 JWT 身份签发。
    24 小时有效期，关闭时不生成任何密钥。服务端 217 项通过。
99. 落地 **Phase 2b 首条消息 sealed**（依赖 2a 开关同时打开）：
    对端设备声明 `supportsSenderCert` 时，发送端把证书放进
    `SenderCertEnvelope`，随 X3DH 密文提交，服务端对首条消息写 `sealed=1`
    与 `from_ref`、`from_user` 为 NULL；收信端先建临时应答链解出正文，
    再验签 + 绑定握手身份公钥 + 走 TOFU，最后把会话落到真实对端槽位。
    服务端证书公钥由 `ServerCertPin` 首次固定（换钥保留旧值）。
    任一环节失败都退回旧格式或按不可解密处理，不猜测发送者。
    服务端 218 项、客户端 123 项、e2e 30 + 11 + 13 项通过。
100. 新增 `SenderCertE2eTest`（11 项）：证书签发 → openssl/Java 独立验签 →
    首条 sealed 落库无 from_user → 收信端按证书还原 → 投递后握手参数擦除 →
    首条 sealed 在收信端历史可查。服务端未启用证书功能时明确 SKIP。
101. 引入**会话标签**（Phase 2c 前置，双写期）：客户端生成 24 字节随机
    Base64URL 标签，随证书信封送达对端并加密落盘；之后每条消息都带上，
    服务端存 `conversation_tag` 并按 `(tag, to_user, id)` 建索引。
    `GET /message/history/{peer}?tags=...` 走标签查询，与盲引用查询并行、
    按 clientMsgId 去重。标签不参与任何签发，服务端无法从它反推用户名。
    此阶段仍保留 `from_ref`（未读/清除/未投递/同步仍依赖它），
    因此仅完成「可按标签归类」，尚未获得隐私收益。
102. 标签相关测试：服务端 `MapperIntegrationTest` 2 项（无 ref 的标签行可查、
    to_user 限定防串会话）、发送/JSON 契约各 1 项；客户端标签存储 3 项、
    信封带标签 2 项；`SenderCertE2eTest` 扩到 14 项（学到标签 + 按标签查历史
    + 响应不回传标签）。服务端 222 项、客户端 128 项、e2e 30 + 14 + 13 项通过。
103. 会话标签覆盖剩余查询：`markReadByTags`、`countUnreadByRefOrTags`
    （双写期把盲引用与标签合并去重，避免同一条消息算两次）、
    `maxTagConversationId` / `wipeTagConversation`、
    `selectParticipantCopyByTag`（同步按标签取原消息）；
    清除时同时写 `tag:<tag>` 清除点，`selectUndelivered` 用
    `greatest(引用游标, 标签游标)` 过滤；客户端在已读/未读/清除请求里带上标签，
    历史同步副本继承标签。服务端 227 项、客户端 128 项、
    e2e 30 + 17 + 13 项通过。
104. **停写 `from_ref`**（带标签的消息）：`send` 在 `conversationTag` 非空时
    不再写 `from_ref`/`from_device_ref`，历史、未读/已读、清除、未投递、
    同步、撤回全部走标签（撤回新增 `selectOwnedCopyByTags` + `tags` 请求字段）。
    新增 `selectTagHistoryForDevice`：收件侧必须选发给本设备的副本，
    否则多设备会拿到别的设备的密文、显示「无法解密」。
    同步副本的方向改为按 `to_user` 推断（标签消息没有发送者列）。
    不带标签的消息仍写盲引用，旧客户端不受影响。服务端 231 项、
    客户端 128 项、e2e 30 + 18 + 13 项通过；`SenderCertE2eTest`
    新增「标签 + token 撤回」。
105. 落地 **Phase 2c 发送者令牌与匿名提交**（默认关闭）：
    独立 RSA 盲签名密钥（`t_rsa_key` 的 `MIM:AUTH:SENDER-TOKEN:*`），
    `GET /token/key` + `POST /token/blind`（按账号 500/日配额）；
    客户端 `BlindRsaToken` 致盲/去盲并自检；`POST /message/anonymous`
    用 `X-Sender-Token` 替代 JWT，只接受带标签的 sealed 消息，
    落库无任何发送者形式。令牌 ±15 分钟有效、Redis `SETNX` 一次性消费
    （重放被拒）、Redis 故障时失败关闭。服务端 234 项、客户端 132 项、
    e2e 30 + 18 + 7 + 13 项通过。
    边界：预密钥领取与多端同步仍会让服务端关联账号，详见第 11 节。
106. 客户端接入匿名提交（默认关闭）：新增不带身份头的
    `Request.anonymousConnector` 与 `AnonymousMessageFeign`；`send` 在
    `-Dmim.anonymousSend=true` 且会话有标签时先申请令牌、走 `/message/anonymous`，
    **任何失败都回退普通发送**（令牌申请失败/服务端未启用/被拒）。
    CI 启动服务端时默认打开证书/令牌/匿名三个开关，让对应 e2e 真正执行而不是 SKIP。
    客户端 134 项、服务端 234 项、e2e 30 + 18 + 7 + 13 项通过。

## 仍需处理

- 消息长度填充只隐藏精确长度，服务端仍可见粗粒度长度、发送时间、参与者与设备。
  sealed sender Phase 1.5 已把发送者列置 NULL，但盲引用仍可被持有服务端密钥者
  关联，且服务端提交时仍从 JWT 知道发送者。提交匿名见
  [docs/sealed-sender-design.md](docs/sealed-sender-design.md) 第 10 节及 Phase 2。
- 撤回凭证仍以发送端为源：另一台设备要靠历史同步拿到凭证，且同步时目标设备
  必须已声明 `supportsRecallToken`；没跑过同步或本机数据被清除时，只能走旧版
  发信人兼容路径撤回。
- 新设备首次信任仍依赖人工核对指纹/60 位安全码，这是 TOFU 的固有局限。
- 邮箱找回密码仍依赖用户填写真实邮箱与 SMTP；恢复码已支持登录、关闭 2FA
  和重置密码，但仍需用户提前离线保存。
- 邮箱与 TOTP 的加密密钥未显式配置 `MIM_EMAIL_KEY` 时会回退到 JWT 私钥；
  JWT 私钥本身可用 `MIM_MASTER_KEY` 加密。现已支持轮换与备份，但生产环境
  仍建议使用 KMS/Secret Manager 托管密钥；同时拿到数据库与主密钥仍可解密。
- 旧版未绑定设备的 JWT 默认仍兼容；客户端全部升级后可设
  `MIM_AUTH_REQUIRE_DEVICE_BINDING=true` 强制其重新登录。
- 服务端已迁移到 Boot 3.5.16 + Java 17；客户端仍为 JavaFX 8，
  CI/部署需要同时准备两个 JDK。
- **Phase 2（进行中）**：2a 证书签发、2b 首条消息 sealed 均已落地但默认关闭；
  e2e 已覆盖证书契约与真实棘轮往返，客户端 `MessageService` 接线仍需人工验证。
  带会话标签的新消息已停写 `from_ref`，「从用户名推导的关联」对这些消息
  不再存在；不带标签的消息（旧客户端/旧会话）仍写引用。混合版本的多设备
  用户需要注意：旧版本设备不认识标签，看不到新会话中被标签化的历史。
  2c 的匿名提交已落地（服务端 + 客户端，默认关闭），且**不等于完全匿名**：
  预密钥领取与多端同步仍可让服务端关联账号，盲签名实现也未经外部审计；
  客户端接入路径尚未在真实界面上验证过。
  见 [docs/sealed-sender-design.md](docs/sealed-sender-design.md) 第 11 节。
- KMS 目前只有外部命令桥接（Vault/AWS/GCP CLI），尚未接 SDK；生产 SMTP
  仍需真实邮件服务器。
- 跨设备历史仍受上面那条限制：没有原文的消息补不回来。

## 许可证

[MIT License](LICENSE) © 2026 June-D

- 界面使用系统字体（macOS: Hiragino Sans GB，Windows: Microsoft YaHei），
  仓库不包含任何不可再分发的商业字体。
- `MIMClient/src/main/resources/images/login/` 下的登录页背景为本项目原创素材。
- `MIMClient/libs/` 下的 DataFX 8 快照 jar 仅用于兼容 JavaFX 8 的本地 system scope 依赖，
  版权归其各自作者所有（Apache License 2.0）。
