# 交接与阶段记录

> 从 README 拆出，保留阶段结论与验收清单，供接手者查阅。当前产品定位见 README《产品基调》。

## 当前状态与交接（2026-11）

当前 `main` 已包含 sealed sender Phase 1.5：

- 当前代码：`main` HEAD（以 `git log -1` 为准；Phase 1 初版为 `a2b6458`）
- CI：见顶部 badge；最近一次服务端/客户端/e2e 全绿
- 回归基线：服务端 **224** 项、协议核心 **129** 项、Rust **19** 项、
  Compose 桌面 **53** 项、e2e **30 + 18 + 8 + 13 + 6** 项（令牌项按开关 SKIP）

### 已完成

- 服务端 Java 17 / Spring Boot 3.5.16；PC 客户端为 Compose Multiplatform（JVM 17）。
  JavaFX 客户端已于 2026-11 移除，端到端脚本迁到 `MIMCore/src/test/e2e`。
- PC 端已覆盖图片与语音（端到端加密媒体）、二次验证管理、本地消息搜索、
  会话置顶、未读定位、安全码变更提醒。
- X3DH + Double Ratchet、按设备多端、TOFU/60 位安全码、OPK 预留后消费。
- 2FA、恢复码、邮箱/TOTP 密钥轮换、JWT 主密钥加密、Redis 安全边界、WS 过期断连。
- 消息长度填充（能力协商）、协议断代（发送者列彻底移除）。
  Compose 客户端尚未接入自有设备同步副本（原 JavaFX 客户端有过实现，随移除退役）。
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
   - `MIMCompose/composeApp/src/desktopMain/kotlin/com/junesoon/mim/app/data/SecureBackend.kt`
     与 `MIMCoreRust/src/session_store.rs`（客户端收发与棘轮状态）
   - `MIMServer/src/main/java/com/junesoon/mim/service/impl/SenderCertServiceImpl.java`
     与 `responses/SenderCertificate.java`（2a 的规范化串）
   - `docs/sealed-sender-design.md` 第 11 节（跨设备同步副本的归属设计；
     原 JavaFX 实现已随客户端移除，重建时以此为准）
   - `MIMServer/sql/e2ee.sql` 末尾的升级补丁
2. **Phase 2（进行中）**：2a、2b 已完成并默认关闭；`SenderCertE2eTest`
   已覆盖服务端契约与真实双棘轮往返，但 **`MessageService` 的接线仍只在
   单元测试层面验证过**，开启前必须人工跑一遍首次联系/TOFU/会话丢失。
   2c 已完成令牌签发/消费、匿名提交入口与客户端接入（默认关闭，`-Dmim.anonymousSend=true`）；
   但**预密钥领取与多端同步仍会关联账号**，所以只能宣称「提交与落库不含发送者」，
   不能宣称完全匿名。下一步：客户端侧拉黑、清理 `idx_ref_*`。见第 11 节。
3. **运维收尾**：KMS SDK 直连（目前只有 CLI 命令桥接）、真实 SMTP、TOFU 人工核对流程。
4. **长期迁移**：全仓库统一 JDK 17；后续移动端另立 target，不再引入 JavaFX。

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
# 全仓库只需要 JDK 17
export MIM_SERVER_JAVA_HOME=/path/to/jdk-17

# 集成测试/本地 MySQL（仓库默认 3306；本地开发常用 13306）
export MIM_TEST_DB_URL="jdbc:mysql://127.0.0.1:13306/mim_test?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true"
export MIM_TEST_DB_USERNAME=root
export MIM_TEST_DB_PASSWORD=

# 全量回归（--e2e 需要另起 MySQL/Redis/服务端）
./verify.sh
./verify.sh --e2e
```

- CI：`gh run list` / `gh run watch <id> --exit-status`；当前 workflow 跑服务端、Rust 对拍与 e2e。
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

## Phase 2 启用前验收清单

**脚本化部分**（`MIMCore/src/test/e2e/AcceptanceE2eTest`）覆盖可断言的协议契约：

```bash
# 8080：三个开关全开；8081：三者全关（用于验证关闭态没有降级路径）
SERVER_PORT=8081 MIM_SENDER_CERT_ENABLED=false \
MIM_SENDER_TOKEN_ENABLED=false MIM_ANONYMOUS_SEND_ENABLED=false \
  java -jar MIMServer-1.0-SNAPSHOT-exec.jar &

cd MIMCore
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
- 服务端与 PC 客户端统一 JDK 17；CI 不再需要 JDK 8/JavaFX。
- **Phase 2 现状**：2a/2b 已生产化（证书默认开启，是消息归属的唯一来源）；
  2c 的令牌与匿名提交已落地但默认关闭，**不等于完全匿名**：预密钥领取与
  多端同步仍可让服务端关联账号，盲签名实现未经外部审计。
  Compose 客户端未实现匿名发送入口；屏蔽、多设备历史、会话丢失提示仍需人工验收
  （见「Phase 2 启用前验收清单」）。
- 客户端屏蔽只能拦截本机展示，服务端仍会投递并计未读。
- KMS 目前只有外部命令桥接（Vault/AWS/GCP CLI），尚未接 SDK；生产 SMTP
  仍需真实邮件服务器。
- 跨设备历史仍受上面那条限制：没有原文的消息补不回来；
  Compose 客户端尚未接入自有设备同步。

### 当前优先级（2026-11）

1. 移动端（Android/iOS）与系统密钥库；需要先装 Android SDK。
2. 自有设备历史同步：先定设备信任模型，避免幽灵设备拿到明文。
3. 媒体保留期：服务端密文目前不会随消息删除自动清理。
4. 头像上传；附件已支持（单文件 ≤8MB，不落服务端明文）。
5. 部署与运维文档（TLS/WSS、限流、备份、密钥轮换）。
