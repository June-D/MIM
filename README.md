# MIM · 端到端加密私密通讯

[![CI](https://github.com/June-D/MIM/actions/workflows/ci.yml/badge.svg)](https://github.com/June-D/MIM/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Spring Boot 服务端 + JavaFX 桌面客户端。**只做一对一私密交流，不做群聊**。
服务端只保存公钥与密文，私钥与所有会话密钥永远只在客户端。

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
- **OPK（一次性预密钥）** 每个只能被领取一次，用完即删，为会话初始消息提供前向安全性。
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
展示分组指纹供用户通过其他可信渠道比对。

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

- **无跨设备历史同步**：新设备加入后只能看到它加入之后的消息（这是端到端加密的固有代价）。
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
  src/test/java/          123 项测试（单元 81 + Mapper 集成 25 + HTTP 集成 17）

MIMClient/
  crypto/                 Hkdf、DhKeyUtil、RatchetService(X3DH+双棘轮)、SessionState、
                          SessionStore、PreKeyStore、IdentityStore、SecretBox、TrustStore
  service/                DeviceIdentity（设备标识）、KeyManager、PreKeyManager、
                          MessageService（按设备收发+棘轮）
  feign/                  Login / Register / Device / Key / PreKey / Message
  websocket/              WSClient（只收推送）
  utils/                  TlsSupport（HTTPS/WSS 信任配置）
  gui/                    Login / Main / Chat / Loading
  src/test/java/          40 项单元测试
  src/test/e2e/           多设备端到端回归（需外部服务，手动运行）
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

启用后监听 `8443`（HTTP 仍可用 8080 便于内网调试；生产应关闭明文端口）。
协议固定 TLS 1.2/1.3，实测协商结果为 `TLSv1.3 + AEAD-AES256-GCM-SHA384`。

### 客户端

`feign-client.properties` 里改：

```properties
feign-client.ribbon.listOfServers = mim.example.com:8443
feign-client.ribbon.IsSecure = true
mim.tls.verifyCertificate = true
```

WebSocket 同步改成 `-Dmim.ws.url=wss://mim.example.com/websocket/test`。
自建 CA 时用 `-Dmim.tls.caFile=/path/to/ca.pem` 追加信任。

> `mim.tls.verifyCertificate=false` 只允许本地调试，会打印警告日志。

## 运行

### 依赖

- JDK 8（客户端需**带 JavaFX** 的 JDK 8，如 Zulu FX 8；Corretto 8 不含 JavaFX）
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

从旧版本升级时，`e2ee.sql` 末尾附了 `ALTER TABLE` 补丁（取消注释后执行）。

### 启动服务端

```bash
export MIM_DB_URL="jdbc:mysql://127.0.0.1:3306/mim_core?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8"
export MIM_DB_USERNAME=root
export MIM_DB_PASSWORD=
export MIM_REDIS_HOST=127.0.0.1
export MIM_REDIS_PORT=6379
mvn spring-boot:run
```

### 启动客户端

```bash
cd MIMClient
mvn compile
mvn exec:java -Dexec.mainClass=com.junesoon.mim.MIMClientStarter
```

首次登录生成身份密钥对，加密存到 `~/.mim/identity/`；会话棘轮状态存到 `~/.mim/sessions/`。

### 自测

```bash
# 一键（推荐）
./verify.sh

# 或分端执行
cd MIMServer && mvn test      # 123 项：单元 81 + Mapper 集成 25 + HTTP 集成 17
cd MIMClient && mvn test      # 40 项：HKDF/SecretBox、X3DH+双棘轮、TOFU、加密落盘
```

**集成测试需要真实 MySQL**（不用 H2：Mapper 依赖 `ON DUPLICATE KEY UPDATE`、
`INSERT IGNORE`、`SELECT ... FOR UPDATE`、分组去重子查询，H2 兼容模式覆盖不全）：

```bash
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS mim_test CHARACTER SET utf8mb4;"
export MIM_TEST_DB_URL="jdbc:mysql://127.0.0.1:3306/mim_test?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true"
mvn test
```

数据库不可用时集成测试会**跳过**（本地友好）；CI 里设置
`MIM_TEST_DB_REQUIRED=true` 则**明确失败**，避免「0 个测试 + BUILD SUCCESS」的假绿。

端到端回归需要先起 MySQL/Redis/服务端，然后：

```bash
./verify.sh --e2e
```

覆盖内容：

| 测试/脚本 | 覆盖点 |
|---|---|
| `MapperIntegrationTest`（25 项） | XML 与 schema 匹配、upsert 语义、`INSERT IGNORE`、`FOR UPDATE` 领取、去重分页、撤回级联 |
| `HttpApiIntegrationTest`（17 项） | 鉴权 401、限流 429、`@CurrentUser`/`@CurrentDevice` 解析、伪造发信人、多设备扇出 |
| `MultiDeviceE2eTest`（13 项） | 多设备注册、按设备分发、各自解密、设备隔离、按 clientMsgId 撤回、注销后停止分发 |
| `WsDevicePushE2eTest`（13 项） | WS 按设备推送：两台设备各收自己那份、多端同步副本、密文互不相同、都能解密 |

## HTTP 接口

所有需要身份的接口都从 `Authorization: Bearer <JWT>` 取用户。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/register/token` | 注册（IP 限流 5/分） |
| POST | `/login/token` | 登录，返回 JWT（IP 限流 10/分） |
| GET | `/user/info` | 当前用户信息 |
| POST | `/user/heartbeat` | 在线心跳续期 |
| GET | `/chat/contacts` | 联系人（分组 + 在线状态） |
| POST | `/device` | 注册/刷新本设备 |
| GET | `/device` | 列出本人所有有效设备 |
| DELETE | `/device/{deviceId}` | 注销设备（停止接收消息） |
| POST | `/key` | 上传/更新本设备公钥（幂等） |
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
    而不是抛一堆 Connection refused；运行前清空限流计数避免跨轮次误伤。

## 仍需处理

- **跨设备历史同步**：新设备只能看到加入之后的消息（端到端加密的固有代价，
  若要做需要「设备间互相加密转发」或可信备份）。
- **端到端脚本受注册限流影响**：连续跑两次会撞上 5 次/分钟的注册限制，
  脚本已改为等待重试，但整体耗时会拉长到 80 秒以上。
- **集成测试串行共享一个库**：`truncateAll` 保证方法间隔离，
  但多个测试类并行执行时需要各自的库（当前 surefire 默认串行，暂无影响）。

## 许可证

[MIT License](LICENSE) © 2026 June-D

- 界面使用系统字体（macOS: Hiragino Sans GB，Windows: Microsoft YaHei），
  仓库不包含任何不可再分发的商业字体。
- `MIMClient/src/main/resources/images/login/` 下的登录页背景为本项目原创素材。
- `MIMClient/libs/` 下的 DataFX 8 快照 jar 仅用于兼容 JavaFX 8 的本地 system scope 依赖，
  版权归其各自作者所有（Apache License 2.0）。
