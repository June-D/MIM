# MIM · 端到端加密私密通讯

[![CI](https://github.com/June-D/MIM/actions/workflows/ci.yml/badge.svg)](https://github.com/June-D/MIM/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Spring Boot 服务端 + JavaFX 桌面客户端。**只做一对一私密交流，不做群聊**。
服务端不持有私钥，只保存必要的路由元数据与密文；私钥与所有会话密钥永远只在客户端。

## 当前状态与交接（2026-11）

当前 `main` 已包含 sealed sender Phase 1.5：

- 当前代码：`main` HEAD（以 `git log -1` 为准；Phase 1 初版为 `a2b6458`）
- CI：见顶部 badge；最近一次服务端/客户端/e2e 全绿
- 回归基线：服务端 **210** 项、协议核心 **129** 项、客户端 **11** 项、e2e **30 + 9 + 18 + 7 + 13** 项

### 已完成

- 服务端 Java 17 / Spring Boot 3.5.16；客户端仍为 Java 8 + JavaFX 8。
- X3DH + Double Ratchet、按设备多端、TOFU/60 位安全码、OPK 预留后消费。
- 2FA、恢复码、邮箱/TOTP 密钥轮换、JWT 主密钥加密、Redis 安全边界、WS 过期断连。
- 消息长度填充（能力协商）、跨设备历史同步、协议断代（发送者列彻底移除）。
- Phase 2a/2b：发送者证书签发/分发/TOFU 固定，证书是消息归属的唯一来源。
- Phase 2c：盲签名令牌 + 匿名提交（默认关闭），客户端接入并支持降级。
- 客户端本地屏蔽名单（匿名消息下唯一可用的滥用拦截手段）。
- Maven Wrapper 3.9.9；CI 覆盖单元、真实 MySQL 集成和 e2e。

### Phase 2 协议断代后的实际边界

本版本已完成**协议断代**：消息只有一条存储路径，没有「旧格式」分支。

- 每条消息都写 `sealed=1` 与 `conversation_tag`，服务端不保存
  `from_user` / `from_device_id` / `from_ref` / `from_device_ref` 任何一列；
- 会话标签由客户端生成（24 字节 Base64URL），随首条消息的证书信封送到对端，
  之后双方每条消息都带上；历史、未读、已读、清除、未投递、同步、撤回全部按标签；
- 发信人归属由服务端签名的**发送者证书**确定（证书在端到端密文里）：
  `MIM_SENDER_CERT_ENABLED` 默认开启，关闭后客户端无法建立新会话；
- 撤回只认发送时生成的一次性 `recallToken` 的哈希，不再有「发信人引用」回退；
- 握手参数只活到投递：首条消息必须带，收信设备确认收到后立刻擦除；
- 同账号其它设备通过已加密的同步副本拿到撤回凭证与会话标签，
  服务端只看到密文与哈希；
- 服务端提交时仍从 JWT 知道提交者；匿名提交（2c）默认关闭，
  且**不等于完全匿名**（预密钥领取与多端同步仍可关联账号）；
- 旧消息（没有会话标签）无法迁移：标签是随机值，服务端无从推导。
  升级前必须执行 `MIMServer/sql/e2ee.sql` 末尾的 Phase 2 清理补丁（会归档并删除）。

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

- `sessionId` 由发起方生成并写入 `SessionState`；首条消息就把证书与标签
  一起放进密文，收信端解出后才知道对端是谁；
- 收件端按 `sessionId -> (peer, device)` 索引棘轮；找不到会话时不要猜测发送者；
- 发送端历史用本地 `PlaintextStore` 的 `sent|peer|clientMsgId`；
  棘轮不能解自己发出的密文；
- 服务端历史按客户端提供的标签查询，用 `to_user` 判断方向（对端 = 我发的）；
- 客户端为每条逻辑消息生成 32 字节随机 `recallToken`，本地加密保存，
  服务端只存 SHA-256；撤回必须原样带回；
- 设备间同步副本共用原消息的 `recall_token_hash` 与标签，一次撤回覆盖所有副本；
  同步副本的 `to_user` 改写为本账号，未投递查询才能命中；
- 握手参数（身份公钥/临时公钥/OPK 编号）在 `/message/delivered` 时清空；
- 跨设备撤回：设备声明 `supportsRecallToken` 后，历史同步把凭证装进
  `SyncEnvelope` 随加密副本送达；
- 多端同步副本使用 `syncOriginalFrom/To` 签名还原归属，不依赖服务端字段；
- 客户端本地屏蔽名单（`blocked|peer`）是匿名消息下唯一的滥用拦截手段；
- 服务端不再有任何「从用户名推导」的索引，也不需要盲引用密钥。

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

- 服务端提交时仍从 JWT 知道发送者；匿名提交默认关闭，且不等于完全匿名；
- 撤回凭证保存在发送端本地：同账号其它设备只有跑过历史同步、且目标设备
  声明了 `supportsRecallToken` 才拿得到；本机数据被清除后只能由发送设备撤回；
- 多端 sender 的其它设备不再接收 raw 密文推送，只能靠历史同步补齐；
- 旧消息（没有会话标签）在升级时会被归档并删除，无法迁移；
- 混合版本的多设备用户：旧版本设备不认识标签，看不到新会话的历史；
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

- **协议断代后的存储**：数据库里没有任何发送者列，只有客户端生成的会话标签；
  但服务端提交时仍从 JWT 知道提交者，预密钥领取也会暴露「A 想联系 B」。
  完全匿名提交（2c）默认关闭，且受这两个边界限制。
- **首条消息依赖发送者证书**：证书关闭或对方版本过旧时无法建立新会话；
  这是用「可归属」换「无发送者列」的直接代价。
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
  src/test/java/          210 项测试（单元 + Mapper/HTTP 真实 MySQL 集成）

MIMCoreRust/             Rust 可行性 spike：对拍 + 互通 + C ABI（需要 cargo）
  src/envelope.rs        标签 / 同步副本 / 发送者证书信封（格式与 Java 逐字节一致）
  src/sender_cert.rs     证书规范化串与 SHA256withRSA 验签、keyId 指纹校验
  src/secure_store.rs    状态加密落盘（AES-256-GCM，密钥来自平台密钥库）
  src/keys.rs            设备密钥管理（身份密钥、签名预密钥、一次性预密钥、轮换）
  src/messaging.rs       首条消息组装/拆解（X3DH + 证书信封 + 标签 + 双棘轮）
  src/session_store.rs   会话存储（按 sessionId、标签学习、sealed 收发、加密落盘）
  java/com/junesoon/mim/core/MimCoreNative.java  JNI 绑定（Android 直接复用）
  include/mim_core.h     C ABI 头文件（Android JNI / iOS cinterop 的公共底座）
  tools/interop.sh       Rust ⇄ Java 真实收发联调
  tools/ffi_test.sh      链接 Rust 静态库的 C 冒烟测试
  tools/jni_test.sh      桌面 JVM 经 JNI 跑完整会话（Android 调用形态）
MIMCore/                 协议核心（不依赖 JavaFX/Feign，移动端与 Rust 移植的基准）
  crypto/                 Hkdf、DhKeyUtil、RatchetService(X3DH+双棘轮)、SessionState、
                          SessionStore、PreKeyStore、IdentityStore、SecretBox、TrustStore、
                          SyncEnvelope、SenderCertEnvelope、TagEnvelope、BlindRsaToken
  src/test/java/          129 项协议单测

MIMCompose/             移动端 UI 骨架（Compose Multiplatform，当前验证桌面目标）
  composeApp/           登录 / 联系人 / 聊天三屏，AppStore 单一状态源
                        SecureBackend 走真实协议：设备密钥 → 首条消息 → sealed 收发
  composeApp/.../data   MessagingBackend 契约 + DemoBackend（内存演示）
  README.md             运行方式与接 Android/iOS 的步骤

MIMClient/
  crypto/                 DemoBot（联调用的演示客户端，依赖 WebSocket，故留在客户端）
  service/                DeviceIdentity（设备标识）、KeyManager、PreKeyManager、
                          MessageService（按设备收发+棘轮）
  feign/                  Login / Register / Device / Key / PreKey / Message
  websocket/              WSClient（只收推送）
  utils/                  TlsSupport（HTTPS/WSS 信任配置）
  gui/                    Login / Main / Chat / Loading
  src/test/java/          11 项单元测试（其余协议测试在 MIMCore）
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
- `t_message.sealed / session_id`：消息一律 sealed，`session_id` 供收件端索引棘轮；
- `t_message.recall_token_hash`：一次性撤回凭证的 SHA-256，撤回必须提交原始 token；
- `t_message.conversation_tag`：客户端生成的会话标签（不透明），
  按 `(tag, id)` 与 `(tag, to_user, id)` 建索引，是历史/未读/清除/同步的唯一索引；
- `t_message` **不再有** `from_user` / `from_device_id` / `from_ref` /
  `from_device_ref` 四列，也没有对应的三个旧索引；
- 唯一索引是 `(client_msg_id, target_device_id)`，不含发送者；
- 升级必须执行 `e2ee.sql` 末尾的 Phase 2 补丁：归档无标签旧消息 → 删除 →
  删列与旧索引 → 清理按用户名记录的清除点。**不可逆**，脚本默认注释状态；
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
# 发送者证书默认为 true；关闭后客户端无法建立新会话。
export MIM_SENDER_CERT_ENABLED=true
# Phase 2c：盲签名令牌与匿名提交，默认关闭（两者要一起开）。
# export MIM_SENDER_TOKEN_ENABLED=false
# export MIM_ANONYMOUS_SEND_ENABLED=false
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

### 消息存储：会话标签与发送者证书

协议断代后消息只有一条存储路径：

- 每条消息都写 `sealed=1` 与 `conversation_tag`，服务端不保存任何发送者列；
- 会话标签由客户端生成，随首条消息的证书信封送达；历史/未读/已读/清除/
  未投递/同步/撤回全部按标签查询，服务端无法从标签反推用户名；
- 发信人归属由服务端签名的发送者证书决定，证书在端到端密文里；
- 幂等唯一键是 `(client_msg_id, target_device_id)`；
- 撤回只认一次性 `recallToken` 的哈希，没有回退路径；
- 服务端在提交时仍从 JWT 知道提交者，**这不是提交时匿名**；
  匿名提交（2c）默认关闭，且受预密钥领取与多端同步的限制。

> `MIM_SENDER_CERT_ENABLED` 必须保持开启。关闭后客户端拿不到证书，
> 无法建立新会话（服务端会拒绝没有标签的消息）。

**升级**：必须执行 `MIMServer/sql/e2ee.sql` 末尾的 Phase 2 补丁。
它会归档无标签旧消息到 `t_message_legacy_archive`，然后删除、删列与旧索引。
标签是随机值，无法回填，因此**旧消息不可能保留在聊天记录里**；
需要保留证据时必须先自行备份归档表。设计说明见
[docs/sealed-sender-design.md](docs/sealed-sender-design.md) 第 12 节。

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
cd MIMServer && ../mvnw test      # 210 项（含真实 MySQL 集成）
cd MIMCore   && ../mvnw test      # 129 项：HKDF/SecretBox、X3DH+双棘轮、信封、盲签名
cd MIMCoreRust && cargo test      # 32 项：原语/ECDH/X3DH/握手/双棘轮/信封/证书验签/状态加密/密钥管理
cd MIMCoreRust && ./tools/interop.sh   # Rust 发起 → Java 解密回信 → Rust 解密（真实互通）
cd MIMCoreRust && ./tools/ffi_test.sh  # C 程序经 C ABI 跑完整会话与状态恢复
cd MIMCoreRust && ./tools/jni_test.sh  # 桌面 JVM 经 JNI 跑完整会话（Android 同款）
cd MIMClient && ../mvnw test      # 11 项客户端用例（依赖已安装的 mim-core）

# 首次构建顺序：先安装协议核心，再构建客户端
cd MIMCore && ../mvnw install -DskipTests && cd ../MIMClient && ../mvnw test

# 移动端 UI 骨架（首次构建会下载 Gradle 与 Compose 依赖）
cd MIMCompose && ./gradlew :composeApp:run
cd MIMCompose && ./gradlew :composeApp:desktopTest

# 接真实服务端（登录 + 联系人）
cd MIMCompose && MIM_SERVER_URL=http://127.0.0.1:8080 ./gradlew :composeApp:run
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
| `MapperIntegrationTest`（31 项） | XML 与 schema 匹配、upsert 语义、`INSERT IGNORE`、`FOR UPDATE` 领取、标签历史/未读/清除、token 撤回 |
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
113. 新增 `AcceptanceE2eTest`：把「Phase 2 启用前验收」里可断言的协议契约
    脚本化（首次联系、证书关闭无降级、匿名关闭、多设备历史），
    跑两个服务端实例（8080 全开 / 8081 全关）；
    纯界面项明确打印 `MANUAL`，当前 12 项通过 + 4 项人工。
137. PC 端补上 **添加联系人**：`GET /chat/search` 搜索（排除自己与已有好友）、
    `POST /chat/requests` 发申请、`GET /chat/requests` 看待处理、同意或拒绝；
    收到 100016~100018 推送即时刷新。联系人列表为空时给出可执行提示。
    联调新增独立账号 `composefrienda` / `composefriendb`，每次先解好友再走
    「搜索 → 申请 → 同意 → 互为联系人 → 搜索不再返回」，连跑多遍都能重复。
136. PC 端补上 **设备注销后的恢复语义**：注销是软删除，被注销设备的令牌被
    服务端拒绝，客户端登录时识别出「已注销」并给出可执行提示；只有「设备行
    缺失」才用同一份密钥重新登记，已有会话与棘轮不受影响。联调断言：已注销
    设备登录报明确错误、换新设备标识后仍能收到消息。
    新增 `MIMCompose/run-desktop.sh`：构建带 JNI 的 Rust 动态库、检查服务端、启动界面。
135. PC 端补上 **未读数与清除会话**：联系人角标以 `GET /message/unread/{peer}` 为准
    （5 秒节流，进入联系人页强制刷新）；聊天页「清除」支持只清自己的与双方清除，
    服务端写入清除点并抹掉密文，客户端同步丢弃本地缓存。联调断言：未读从 ≥1 归零、
    单方清除后对方记录仍在、清除后仍能继续收发、双方清除后对方本地也为空。
134. PC 端补上 **设备管理与安全码**：设备页列出本账号设备并可注销其它设备；
    聊天页按对端每台设备展示 60 位安全码（先按公钥字节序排序再 SHA-256，
    两端结果一致，可线下核对）。联调断言设备列表含本机、双方安全码对称。
    测试改为登录后按 `test` 前缀清理自己造的设备，避免撞每账号 10 台上限，
    也不会误删用户真实的 `desktop-` 设备。
133. PC 端接入 **WebSocket 推送**：连 `/websocket/*`，握手带令牌与设备标识，
    收到「新消息 / 已读回执 / 撤回」事件即时触发一次 HTTP 同步；保留 3 秒轮询
    兜底，断线 3 秒重连、25 秒无帧发心跳。推送只做「叫醒」，落库与鉴权仍走
    HTTP，丢失推送不会破坏一致性。联调用「A 发送 → B 的监听立即收到信号」
    验证，不依赖轮询。
132. PC 端补齐 **撤回、已读回执、离线队列**：发送时生成一次性撤回凭证
    （服务端只存哈希），本地缓存凭证供撤回使用；打开会话即标记已读；
    没有 WebSocket 时用「当前会话历史轮询」补偿已读与撤回状态。
    投递失败进入离线队列，保存的是已组装的密文，重试只重发、不重新推进
    棘轮，避免重复消息与状态失步。联调断言「已读可见」「撤回可见」。
131. MIMCompose 接入 **真实收发**：新增 `SecureBackend` 与 `LocalVault`。
    登录后自动补设备密钥；发送时按设备查会话，首条消息走证书+标签，之后走
    sealed；接收轮询 `/undelivered`，Rust 解密后写入加密缓存并回执已投递。
    保险库（密钥包/会话/消息）全部 AES-GCM 加密落盘，重启后历史与棘轮都还在。
    `AppStore` 增加 3 秒轮询，把新消息合并进会话列表与未读计数。
    联调测试跑通「A 发 → B 解 → B 回 → A 解」与重启后缓存读取。
    限制：历史只来自本地缓存；桌面端保险库密钥仅受文件权限保护。
130. Rust 补齐 **会话存储与 sealed 回信**：按 `sessionId` 索引双棘轮状态，
    记录从标签信封学到的标签（去重收敛），`compose_sealed` 包标签信封后加密，
    `open_sealed` 解密后学习标签；解密失败不写回状态，坏消息不会让棘轮失步。
    整个存储可经 `secure_store` 加密落盘，重启后能继续解密。
    C ABI/JNI 增加 `session_store_insert` / `compose_sealed` / `open_sealed`。
    Kotlin 联调把首条消息后的回信也跑通（JNI 往返）。Rust 测试 32 项。
129. Rust 补齐 **首条消息组装与拆解**：`compose_first_message` 完成 X3DH、
    把服务端签发的发送者证书与会话标签放进密文、再用双棘轮加密，输出服务端
    可直接接收的字段（含握手参数与一次性预密钥消耗）；`open_first_message`
    由接收方重建会话、解密并拆证书信封。C ABI/JNI 以 JSON 进出。
    Kotlin 联调测试打通真实链路：A 组装 → 服务端落库 → B 拉离线消息 → B 拆解，
    断言明文、证书用户名/设备、身份公钥一致与会话标签。Rust 测试 28 项。
128. Compose 侧打通 **设备登记与密钥上传**：新增 `KeyRegistry`，按服务端契约
    登记设备、上传身份公钥（`POST /key`）、签名预密钥与一次性预密钥；
    密钥材料由 Rust 核心经 JNI 生成，私钥不出进程。联调测试在真实服务端上
    验证版本、算法、指纹与剩余一次性预密钥数量。
    **同时修复一个真实缺陷**：服务端用 BCrypt 校验明文口令，而 Java 客户端
    上传公钥时发的是 Base64，导致新增/更换身份公钥永远失败；已改为明文，
    并修正请求字段说明与校验器参数名。
127. Rust 核心补上 **设备密钥管理**：身份密钥、签名预密钥（签名串 `"{id}|{SPKI}"`、
    `SHA256withECDSA`）与一次性预密钥的生成/轮换/取用/补充；一次性预密钥
    取到即删，签名预密钥只保留最近 3 个。密钥包序列化后用 `secure_store`
    加密落盘。C ABI 与 JNI 暴露 `key_bundle_generate` / `signed_pre_key_verify`。
    与 Java 的签名格式用固定向量对拍：Java 签的签名 Rust 能验，改 ID 或改
    公钥即失败。Rust 测试 26 项。
126. MIMCompose 接入 **真实服务端传输**：新增 `HttpBackend`（Ktor + kotlinx
    serialization），登录走 `POST /login/token`（令牌绑定 `X-Device-Id`），
    联系人走 `GET /chat/contacts` 并展开分组。设置 `MIM_SERVER_URL` 时启用，
    否则仍用内存演示后端。聊天历史与发送**显式未接入**（不返回假数据），
    它们依赖端到端加密链路，是协议层迁移的下一步。联调测试用新注册账号
    走「注册 → 登录 → 联系人」，不依赖预置账号。
125. Rust 核心补上 **加密状态存储**：会话状态用 AES-256-GCM 加密成三行文本
    （版本串兼作 AAD），密钥由 Android Keystore / iOS Keychain 提供，
    nonce 每次新生成。C ABI 与 JNI 都暴露 `state_encrypt/state_decrypt/random_bytes`，
    错误密钥、篡改密文、错误 nonce、错误版本串全部拒绝；6 项单测 + C/JNI
    冒烟覆盖。这样移动端才有真正的「私钥不明文落盘」方案。
124. 新增 **MIMCompose**（Compose Multiplatform UI 骨架）：登录 / 联系人 / 聊天
    三屏，`AppStore` 收口全部状态与动作，`MessagingBackend` 定义传输契约，
    `DemoBackend` 只在内存里跑通交互。桌面目标可编译、可启动，状态流 3 项
    单测通过。本机没有 Android SDK，因此未声明 `androidTarget()`；README 写明
    接入步骤（加 target、把 UI 移到 commonMain、JNI 用 `MimCoreNative`）。
123. Rust 核心补上 **JNI 绑定**：`MimCoreNative`（Java 类）经 JNI 调用同一份
    Rust 核心，握手返回不透明句柄，消息与状态用 JSON 字节数组，失败抛异常。
    `tools/jni_test.sh` 在桌面 JVM 上跑完整会话 + 重复投递拒绝 + 状态恢复，
    Android 只需把 `.so` 放进 `jniLibs` 并复用这个类。这一步不需要 Android SDK
    就能验证调用形态。
122. Rust spike 补齐 **信封层与证书验签**：标签信封、同步副本信封、
    发送者证书信封（7 段 / 带会话标签的 8 段）全部按 Java 格式解析与打包；
    证书用 `SHA256withRSA` 验签，并校验 `keyId = SHA-256(服务端公钥 DER)`、
    5 分钟时钟偏差与身份公钥一致性。真实 RSA 密钥与签名入库为基准向量，
    篡改字段/过期/错误 keyId/非法签名均被拒绝。`cargo test` 12/12。
121. Rust 核心暴露 **C ABI**（`include/mim_core.h`）：不透明 `MimSession` 句柄、
    握手/加密/解密/状态保存恢复、库分配缓冲与错误码；消息与状态用 JSON，
    新增字段不破坏 ABI。`tests/c/ffi_test.c` 链接 Rust 静态库跑完整会话、
    拒绝重复投递、并验证「保存 → 释放 → 恢复 → 继续解密」。
    Android 的 JNI 与 iOS 的 cinterop 都只依赖这一层。
120. Rust spike 完成 **真实跨语言互通**：Rust 作为发起方用 X3DH 建会话并加密
    首条消息，Java 侧 `RatchetService` 解密后回信，Rust 再解密该回信。
    两边实现独立，只共享线上格式；`tools/interop.sh` 串起三步，CI 与
    `verify.sh` 都会执行。同时把 `RatchetState` 补上 `snapshot()/from_snapshot()`
    与 `WireMessage`（dh/pn/n/iv/ciphertext），这就是未来 FFI 的状态与消息边界。
119. Rust spike 补齐 **双棘轮状态机**：`RatchetState` 对齐 `RatchetService`，
    覆盖发送/接收链推进、DH 换步、跳键缓存、乱序解密与重放拒绝。
    `RatchetVectors` 用真实 `RatchetService` 驱动一段确定性对话
    （A 发起 → B 回信 → A 连发 → B 乱序先解后一条）导出 8 步完整状态；
    Rust 逐步断言明文、消息头、密文与根/链/棘轮密钥/计数/跳键。
    为让向量可复现，`RatchetService` 增加包内可见的换步密钥与 IV 注入点
    （默认仍是安全随机，生产路径不变）。`cargo test` 9/9；CI 同时重新
    生成两份向量并 `diff`，防 Java 侧悄悄漂移。
118. Rust spike 补齐 **P-256 ECDH 与 X3DH**：固定密钥材料入库，
    Rust 端解析 PKCS#8/SPKI 计算 DH1–DH4，并与 Java 的初始共享密钥
    （`HKDF("MIM/v2/initial")`）逐字节一致；DH 棘轮步（ECDH → root/chain）
    同样对拍通过。`cargo test` 6/6。
117. 新增 **MIMCoreRust** 可行性 spike：Rust 实现 HKDF、AES-256-GCM、
    棘轮根/链 KDF、AAD 序列化、标签/同步信封、证书规范化串与 SHA-256，
    由 `MIMCore/tools/ExportVectors.java` 生成 Java 基准向量，
    `cargo test` 逐字节对拍。用于验证 Android/iOS 的 Rust + FFI 路线。
    目前已覆盖 P-256 ECDH 与 X3DH 初始密钥（DH1..DH4 + HKDF），
    尚未覆盖完整棘轮状态机与平台存储；二者是下一步。
116. 抽出协议核心 **MIMCore**（`mim-core`）：把 26 个纯协议类（X3DH、双棘轮、
    会话/预密钥存储、同步/证书/标签信封、盲签名）与 `SenderCertificateView`
    迁出客户端，只依赖 JDK + Lombok + slf4j；129 项协议单测随之迁移。
    `MIMClient` 改为依赖 mim-core，`verify.sh` 与 CI 增加 install/test 步骤。
    这层是 Android/iOS（Compose）与 Rust 移植的协议基准，不含任何 UI/传输代码。
    服务端 210 项、核心 129 项、客户端 11 项、e2e 全绿。
115. 完成 **Phase 2 人工验收**（2026-11-19）：清单 6 项全部通过
    （首次联系、证书关闭报错、匿名真机发送、屏蔽/解除、多设备历史、
    会话丢失提示）。过程中修复：OkHttp/kotlin 冲突、离线消息未拉取、
    证书信封未拆包；补齐「已屏蔽」标识与标签信封。
114. 修复**会话标签不一致导致消息在历史里消失**：双方可能各自生成标签，
    而标签只在会话未确认时随证书信封传一次；会话确认后另一方永远学不到，
    用它发的消息就查不到（表现为「收不到」/「什么都不显示」）。
    现在每条已确认会话的消息都带一层 `TagEnvelope`（约 40 字节），
    接收端统一学习标签，双方最终收敛。
    同时联系人列表会显示「已屏蔽」标签，屏蔽后无需凭记忆判断。
    客户端 140 项通过。
112. 修复**证书信封在普通解密路径被当成正文显示**：发送端在会话未确认时会
    继续把证书带在消息里，而接收端可能已有会话，走的是普通解密路径。
    现在所有非同步消息解密后统一拆 `SenderCertEnvelope`，再剥离 padding；
    旧版本已缓存的信封原文也会在读取缓存时自动拆包。
    客户端 137 项通过。
111. 修复**离线消息不再被拉取**：`/message/undelivered` 之前没有任何调用点。
    标签化历史后这会导致死循环：新会话的标签藏在那条离线消息里，
    不拉消息就学不到标签，历史永远是空的。现在登录后立即补拉一次，
    并在 5 秒轮询里继续拉取；解密后确认投递。
110. 修复「单台设备缺预密钥阻断整条消息」：`PreKeyManager.fetchVerifiedBundle`
    收到服务端「尚未发布预密钥」时改为抛 `PreKeyUnavailableException`，
    由 `send` 跳过该设备继续发给其它就绪设备；同时清理了联调时误建的
    无预密钥测试设备（已注销并删除其公钥行）。
109. 修复客户端**依赖冲突导致登录无反应**：`feign-okhttp 13.18` 解析到
    OkHttp 5.5.1，而 Spring Boot 2.7 BOM 把 `kotlin-stdlib` 锁在 1.6.21，
    运行时缺 `kotlin.enums.EnumEntriesKt`（`NoClassDefFoundError`），
    Feign 初始化即失败。`pom.xml` 的 `dependencyManagement` 把
    `kotlin-stdlib` / `-common` / `-jdk8` 固定到 1.8.21（仍兼容 Java 8），
    并新增 `HttpClientDependencyTest` 在测试阶段拦同类冲突。
    客户端 136 项通过。
108. **Phase 2 协议断代**：彻底移除 `from_user` / `from_device_id` /
    `from_ref` / `from_device_ref` 与 `MetadataRef`（HMAC 盲引用机制）：
    `send` 只接受 sealed + 会话标签，历史/未读/已读/清除/未投递/同步/撤回
    全部按标签，撤回只认 token；同步副本的 `to_user` 改写为本账号、
    不再向发送者其它设备推送原始密文（它们解不开）。
    新装库直接不含旧列；旧库执行 `e2ee.sql` 末尾 Phase 2 补丁
    （归档 → 删除无标签旧消息 → 删列与旧索引）。
    服务端 210 项、客户端 135 项、e2e 30 + 18 + 7 + 13 项通过。
107. 客户端**本地屏蔽名单**：`PlaintextStore` 新增 `blocked|peer` 加密项，
    `MessageService.block/unblock/isBlocked`；解密出口统一屏蔽为
    「[已屏蔽该联系人]」（仍正常解密、推进棘轮，解除后不丢密钥）；
    被屏蔽会话不参与跨设备历史同步转写；联系人右键菜单增加「屏蔽此人 / 解除屏蔽」。
    匿名消息里服务端不知道发送者，屏蔽只能落在客户端。客户端 135 项通过。

## Phase 2 启用前验收清单

**脚本化部分**（`MIMClient/src/test/e2e/AcceptanceE2eTest`）覆盖可断言的协议契约：

```bash
# 8080：三个开关全开；8081：三者全关（用于验证关闭态没有降级路径）
SERVER_PORT=8081 MIM_SENDER_CERT_ENABLED=false \
MIM_SENDER_TOKEN_ENABLED=false MIM_ANONYMOUS_SEND_ENABLED=false \
  java -jar MIMServer-1.0-SNAPSHOT-exec.jar &

cd MIMClient
javac -encoding UTF-8 -cp "target/classes:$(cat /tmp/cp.txt)" \
  -d /tmp/e2e src/test/e2e/AcceptanceE2eTest.java
java -cp "/tmp/e2e:target/classes:$(cat /tmp/cp.txt)" \
  com.junesoon.mim.crypto.AcceptanceE2eTest
```

当前结果：**12 项协议断言通过**（首次联系、证书关闭、匿名关闭、多设备历史），
另有 4 项纯界面项会打印 `MANUAL`，必须人工确认。

**验收结果（2026-11-19）**：清单 6 项全部通过。

| # | 项目 | 结果 |
|---|---|---|
| 1 | 首次联系收发、解密、回复 | ✅ 真机 |
| 2 | 证书关闭时明确报错 | ✅ 真机（无静默降级） |
| 3 | 匿名开/关各跑一次首次联系 | ✅ 真机（`/message/anonymous` + 令牌消费） |
| 4 | 屏蔽 / 解除屏蔽 | ✅ 真机（占位与原文本切换正常） |
| 5 | 多设备历史 | ✅ 脚本（`AcceptanceE2eTest`） |
| 6 | 会话丢失提示 | ✅ 真机（占位明确、不崩溃） |

验收过程中修复的三个真实缺陷：OkHttp/kotlin 依赖冲突、离线消息未拉取、
证书信封没有在普通解密路径拆包；另加「已屏蔽」标识与标签信封。

纯界面项仍必须在真实界面上人工跑一遍，
全部通过后再考虑打开 `MIM_SENDER_CERT_ENABLED` / `MIM_SENDER_TOKEN_ENABLED` /
`MIM_ANONYMOUS_SEND_ENABLED` 与客户端 `-Dmim.anonymousSend=true`：

1. 首次联系：A 给从未聊过的 B 发消息，B 能收到、能解密、能直接回复；
2. 服务端关闭证书功能时，A 的发送给出明确错误（不再有明文降级路径）；
3. 客户端匿名关闭/开启各跑一次首次联系，确认历史、未读、清除都正常；
4. 屏蔽：B 屏蔽 A 后，A 的新消息显示「已屏蔽该联系人」，解除后恢复正常；
5. 多设备：A 的两台设备都能看到同一会话的历史（含被标签化的新消息）；
6. 会话丢失：删掉本地会话后收到 sealed 消息，提示明确且不崩溃。

## 仍需处理

- 消息长度填充只隐藏精确长度，服务端仍可见粗粒度长度、发送时间、参与者与设备。
  协议断代后数据库没有发送者列，但服务端提交时仍从 JWT 知道提交者；
  匿名提交默认关闭，且受预密钥领取/多端同步的限制（见设计文档第 11 节）。
- 撤回凭证仍以发送端为源：另一台设备要靠历史同步拿到凭证，且目标设备
  必须已声明 `supportsRecallToken`；没跑过同步或本机数据被清除后无法撤回。
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
- **Phase 2 现状**：2a/2b 已生产化（证书默认开启，是消息归属的唯一来源）；
  2c 的令牌与匿名提交已落地但默认关闭，**不等于完全匿名**：预密钥领取与
  多端同步仍可让服务端关联账号，盲签名实现未经外部审计。
  客户端匿名路径与屏蔽、多设备历史、会话丢失提示仍需人工验收
  （见「Phase 2 启用前验收清单」）。
- 客户端屏蔽只能拦截本机展示，服务端仍会投递并计未读。
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
