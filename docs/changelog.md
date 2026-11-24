# 变更记录（相对原始雏形）

> 从 README 拆出，保留历史决策脉络；新改动继续追加在顶部。

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
140. PC 端修正 **注册流程**：注册只建账号，成功后回到登录页预填用户名，
    首次登录才登记设备；注册页去掉设备名与「记住账号密码」。
    注册请求改用显式 `@Serializable` DTO——此前用 `buildMap` 组装，
    Ktor 拿到 `MapBuilder` 运行时类型，kotlinx.serialization 直接抛
    「Class 'MapBuilder' is not registered」。
    邮箱按服务端同一正则与 254 长度先做客户端校验。联调新增「注册后能登录」。
139. PC 端补齐 **账号与隐私操作**：注册（含用户协议 2026-11、可选找回邮箱、
    设备名）、忘记密码（邮箱验证码，未配置 SMTP 时原样提示）、记住账号密码
    （`~/.mim/remember.bin`，AES-GCM + 0600，仅登录页提供；退出登录保留预填
    但关闭自动登录）、退出登录。
    聊天页支持好友备注、屏蔽/解除屏蔽（本地丢弃明文但继续推进棘轮）、
    清除会话、双向移除好友。联调断言：备注覆盖显示名、移除后双方都看不到对方、
    屏蔽期间收不到消息但解除后能继续收。
138. PC 端完成 **界面重做**：暖白纸面 + 深青主色，登录页分栏品牌区、
    首字母头像（颜色由名字稳定推导）、按日期分组的聊天气泡、
    联系人搜索与未读角标、空态与提示条。图标用 Canvas 本地绘制，不新增依赖。
137. PC 端补上 **添加联系人**：`GET /chat/search` 搜索（排除自己与已有好友）、
    `POST /chat/requests` 发申请、`GET /chat/requests` 看待处理、同意或拒绝；
    收到 100016~100018 推送即时刷新；好友同意后联系人列表立刻出现对方。
    联系人列表为空时给出可执行提示。
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

**第八轮（PC 客户端与安全内核）**

- `MIMCoreRust` 成为唯一密码学执行内核，桌面端经 JNI 调用；C ABI 预留给 iOS。
- `MIMCompose` 完成 PC 端：注册/登录/二次验证码/记住账号、联系人申请与备注、
  屏蔽、双向移除、一对一收发、撤回、已读、未读、清除、离线队列、
  WebSocket 推送、设备管理、安全码、界面重做与本地加密保险库。
- 端到端脚本从 JavaFX 客户端迁到 `MIMCore/src/test/e2e`：它们原本就只依赖
  mim-core 与 JDK 自带 HTTP，搬家后去掉 JDK 8 / JavaFX 依赖。
- JavaFX 客户端 `MIMClient` 已移除；Compose 登录新增「二次验证码 / 恢复码」，
  避免 2FA 账号失去可用客户端。原客户端的匿名发送入口、跨设备历史同步
  与相关单测随模块退役，重建时以 `docs/sealed-sender-design.md` 为准。

- Compose 新增「安全设置」：二次验证开启（共享密钥 + otpauth + 验证码确认）、
  关闭与恢复码重新生成（密码 + 验证码/恢复码）；启用/关闭后立即换用服务端
  新签发的令牌，其它设备被强制重新登录。联调覆盖「开启 → 无码登录被识别 →
  恢复码登录 → 关闭 → 无码登录恢复」。

- 新增端到端加密的图片与语音：文件用一次性 AES-256-GCM 密钥加密后上传
  `/media`，消息密文里只带媒体 id、密钥、类型与尺寸/时长；服务端只存密文。
  单文件 8MB，密文缓存在保险库 `media/`，清除会话时一并删除本地副本。
- 输入区改造：Enter 发送、Shift（或 Ctrl/Alt/⌘）+Enter 换行；
  中文输入法拼写中回车是选词，不会误发。

- 新增本地消息搜索：只在已缓存的加密消息里检索文字/图片说明/语音说明，
  命中后进入会话并滚动定位；服务端无明文，换设备后旧消息不可搜。

- 新增会话置顶（本机偏好持久化）、未读定位（滚到第一条未读并画分界）、
  安全码变更提醒（记录见过的值，变化时要求重新线下核对）。

- 桌面端改版：宽窗口下改为「左列表 + 右聊天」两栏工作区，聊天细节与列表同屏；
  加入悬停/按下态与手型光标、图标 tooltip、行悬停露出「⋯」、消息分组与时间下移、
  「回到最新」浮动按钮、⌘/Ctrl+K 搜索、密码显隐、统一 40dp 紧凑搜索框。

- 多设备历史同步（界面已开放）：同账号的另一台设备可拉取最近历史
  （最近 50 条 / 30 天文本）。源设备弹窗显示请求设备 id 与**身份指纹**，
  同意后才重新加密转发；批准结果记在本机 `trusted_devices.bin`（不上报服务端），
  设备页显示「已批准历史同步」并可随时撤销。新设备首次登录若无本地历史，
  会自动发一次请求并提示「需要在那台设备上确认」。

- 多设备同步：协议链路打通（界面仍未开放）。A 设备把最近 50 条 / 30 天内的
  文本历史重新加密给本账号的另一台设备，接收端用来源设备公钥验签后按信封里的
  `originalFrom/To` 还原归属。过程中发现并修掉一个服务端缺陷：`syncOne` 没有
  复制 `session_id`，导致副本永远解不开（真机 e2e 才暴露出来）。

- 多设备同步第一步（尚未在界面开放）：Rust 内核新增身份密钥签名/验签导出
  （`signWithIdentity` / `verifyWithIdentity`），客户端补齐同步副本 DTO 字段
  （`syncOriginalFrom/To/Device`、`syncSignature`、`syncFromDevice`）并接住
  WS 100022「另一台设备请求历史」。转发、确认弹窗与接收端归属还原待下一提交，
  设计见 `docs/device-sync-design.md`。

- 运维：数据迁出 /tmp。`./start.sh` 一条命令拉起 MySQL + Redis + TLS 服务端，
  数据放 `~/MIM-data/{mysql,tls,media,logs}`，派生密钥放 `~/.mim/keys`。
  起因是 /tmp 被清理导致 `t_rsa_key` 丢失、RSA 私钥重生，连带二次验证密钥全废。
  已实测：停→启一轮后登录正常、媒体密文写入新目录、App 会话自动恢复。

- 服务端：修复「二次验证码永远错误」的运维陷阱。TOTP 密钥用「主密钥」加密，而未配置
  `MIM_EMAIL_KEY` 时主密钥 = SHA-256(RSA 私钥)——RSA 私钥一旦重新生成（此前
  `t_rsa_key` 的数据文件随 /tmp 一起丢失被重建），所有已存 TOTP 密钥就永久解不开，
  登录却报「二次验证码错误或已被使用」，把用户引向「我输错了」的错觉。
  改动：错误文案区分「密钥不可用（请用恢复码登录）」与「验证码错误」；
  `run-server.sh` 自动使用 `~/.mim/keys/{email,master}.key`（不存在则提示生成），
  让派生密钥跨重启稳定。

- iOS 功能收尾与整机验证：会话列表预览（媒体占位）、下拉刷新、长按清除会话、
  离线提示条（WS 连接状态）、应用图标未读角标、图片全屏查看与分享、文件解密导出、
  设备页左滑注销 / 清除本地记录。修掉两个体验缺陷：媒体消息预览为空白；
  `pushConnected` 只在收到事件时才置真，导致「连着却显示断开」。

- iOS 多设备历史同步（收发两端都实现）：新设备空库时请求同步，旧设备把最近 30 天 /
  50 条加密转发（身份私钥签名归属、与目标设备会话加密），新设备解密验签后入库并标注
  「来自另一台设备」。两台模拟器实测：`sync_from`/`client_time` 均正确保留原始时间。
  修掉两个缺陷：同步副本必须带**原消息自己的会话标签**（一条逻辑消息在不同设备上
  标签不同，用错会得到「没有可同步的原消息」）；导出循环里首条消息后误用 `break`
  导致只导出一条。

- iOS 体验完善：登录态持久化（令牌存 Keychain，冷启动静默恢复，重开 App 不用重新登录）；
  会话列表与聊天头部加头像（无头像回落首字母 + 哈希配色）；时间按「今天/昨天/日期」
  格式化并在聊天页插日期分隔；错误横幅可手动关闭。

- 修复真机点麦克风「秒退」：`NSMicrophoneUsageDescription` 被我手工写进了
  `Sources/Info.plist`，而 XcodeGen 每次 generate 都会用 `project.yml` 的
  properties 覆盖它——于是包里没有这条 key，请求麦克风时系统直接终止进程。
  改到 `project.yml` 的 `info.properties` 里，并在 README 写明「plist 属性
  以 project.yml 为准」。

- iOS 撤回与清除会话：长按气泡撤回（服务端按 clientMsgId 撤掉所有副本并抹密文）、
  右上角菜单单方清除会话；收到 100014 时按 clientMsgId **更新原消息**而不是追加。
  修掉两个问题：撤回接口强制要求 `tags`（`40001 缺少会话标签`），iOS 从会话索引里
  取该对端的全部标签；聊天页原来不显示错误条，撤回失败时表现为「点了没反应」。

- iOS M3 收尾：任意文件（`.fileImporter` + security-scoped 访问）与语音
  （`AVAudioRecorder` 录 m4a、`AVAudioPlayer` 播放，解密到临时文件播完即删）
  都已接入，与图片共用同一条「加密 → `POST /media` → 消息带引用」的路径。
  语音录制依赖麦克风权限弹窗，无法脚本化验证；播放链路与气泡状态已实测。

- iOS M3 图片：相册选图 → 一次性密钥加密（`MIM-STATE-1`）→ `POST /media`
  （>8MB 自动分片）→ 消息正文带 `{id,key,size,mime}` 引用；接收端按 id 取密文解密显示，
  本地只缓存密文。与桌面端格式互通（双向实测：App 发、命令行收；命令行发、App 显示）。
  修掉一个判断缺陷：媒体载荷不能用 `{"v":1,"kind":` 前缀识别（JSON 键顺序不保证），
  改按解码结果 + kind 白名单；老数据里的载荷在登录时自动升级为附件消息。

- iOS 长连接与已读：WebSocket（心跳 25s、断线 3s 重连）+ 会话未读红点 +
  进入会话标记已读（带 `clientMsgIds` 跨设备副本）+ 对方读过后显示「已读」。
  修掉一个静默缺陷：已读回执类型是 **100012**（我写成 100011），
  表现为「消息到了但永远显示未读」；新增 `PushEventTests` 钉住事件编号。
  轮询降到 20 秒一次，只作兜底。

- iOS M2 界面完成：会话列表 + 聊天页（气泡、输入区、3 秒轮询），本地库整体加密落盘
  （`mim_state_encrypt`，密钥在 Keychain，磁盘上 `grep` 不到明文），
  发送/接收/持久化在 iPhone 17 模拟器上实测通过。
  修复一个隐蔽缺陷：`GET /message/undelivered` 不会自动置已投递，必须补调
  `POST /message/delivered?beforeId=`，否则同一密文每轮重复下发。

- iOS M2 协议核心打通（`swift run MIMKitSmoke --chat`）：两个临时账号注册 →
  发钥 → A 发首条消息（X3DH + 证书信封）→ B 解密 → B 回信（双棘轮）→ A 解密，
  全部走 Swift + 真服务端。踩到并修掉 6 个契约问题：`/cert/sender` 而非 `/cert`、
  `/message` 而非 `/message/send`、`/prekey` 返回列表、公钥 Base64↔hex 三层不一致、
  `alg` 只认固定字符串、会话存储必须 `{"sessions":[]}` 且 `state.skipped` 必填；
  另有查询串被 `URLComponents.path` 转义成 `%3F` 导致的「用户名不合法」。

- iOS M1 在模拟器（iPhone 17 / iOS 27.2）跑通：登录 → 发布密钥 → 设备列表，
  密钥包存 Keychain 并显示身份指纹。踩到两个 iOS 特有问题并修掉：
  模拟器关签名导致 Keychain `-34018`（症状像「登录了但没身份」）；
  登录写在 `.task` 里会被视图切换取消（`transport("cancelled")`）。
  另加 DEBUG-only 的环境变量预填（`SIMCTL_CHILD_MIM_DEMO_*`），方便截图与联调，
  发布版不编译该路径。

- iOS M1 的 HTTP 链路打通并经真服务端验证：`MIMKitSmoke --login` 跑通
  「登录 → 登记设备 → 上传身份公钥 + 签名预密钥 + 一次性预密钥 → 设备列表 →
  注销」。过程中修掉两个真问题：`MIMClient` 误用 snake_case 编码（服务端是
  camelCase，`deviceId` 被编成 `device_id` 导致 400）；`data` 形状不统一时
  为类型匹配反复改结构体（改用「忽略 data」的类型）。App 侧首次登录会发布密钥，
  否则别人查不到预密钥、永远无法给它发起会话。

- iOS M0 完成：`build-ios.sh` 产出 xcframework（设备 + 模拟器 arm64），
  `xcodegen` 生成工程，`MIMiOSApp/build.sh` 编译链接成功（arm64、未签名）。
  过程中解决三个真实障碍：`xcodebuild -runFirstLaunch` 缺失导致插件加载失败、
  `binaryTarget` 路径必须在包内、静态库 xcframework 必须自带 modulemap。
  剩余：模拟器运行时未下载（约 7GB），或接真机签名。

- iOS 库层落地：新增 `MIMiOS/MIMKit` Swift Package——`MimCore`（C ABI 封装：
  密钥包 / 首条消息 / 签名验签）、`Models`（与 Rust DTO 逐字对应）、
  `Keychain`（AfterFirstUnlockThisDeviceOnly + deleteAll）、
  `MIMClient`（登录 / 设备 / 注销，统一信封与错误码）。不依赖 XCTest 的
  `swift run MIMKitSmoke` 在 macOS 上跑通全链路；`ContractTests` 钉住
  `identity_private`、`peer_signed_pre_key_id`、`certificate.device_id` 等字段名。
  `tools-sync-header.sh --check` 防止包内头文件与 Rust 侧漂移。

- Swift 桥接实测：`tools/swift_abi_smoke.sh` 用 macOS 自带 swiftc 直接调 C ABI，
  跑通「密钥包生成 → 组装首条消息 → 对端解出明文 → 身份签名 + 篡改被拒 →
  非法输入返回错误码」。这段 Swift（`tools/swift/abi_smoke.swift`）就是 iOS 侧
  要写的桥接层，装好 Xcode 后可直接搬进工程。

- iOS 准备工作（不需要 Xcode 就能做的那部分）：C ABI 补齐首条消息与身份签名
  四个导出（`mim_compose_first_message` / `mim_open_first_message` /
  `mim_sign_with_identity` / `mim_verify_with_identity`）——此前只有 JNI 有，
  iOS 拿不到会直接卡在 M1。新增 `tests/ffi_abi_test.rs` 直接调 C ABI 验证
  X3DH 往返、签名往返与非法输入的错误码；`docs/ios-api-map.md` 把 Swift 侧
  要用的 C ABI、HTTP/WS 接口与三条时序整理成清单。

- 移动端立项（iOS 优先）：`docs/ios-plan.md` 记录环境实测（本机无 Xcode，
  Rust 交叉编译在链接阶段失败的真实报错）、架构选择（SwiftUI + Rust 静态库，
  协议核心已有 C ABI 不必重写密码学）、五个里程碑与需要账号/签名的前置条件；
  新增 `MIMCoreRust/tools/build-ios.sh`（环境检查 + 双架构 xcframework）。

- 附件支持分片上传：单文件上限从 8MB 提到 100MB。超过 8MB 时客户端切成 1MB 分片，
服务端逐片落盘、`complete` 时按顺序合并并校验每片长度（缺片/长短不符直接拒绝，
不会产生半张媒体）。同一片重复上传幂等；按内容哈希记住 uploadId，断线后重发
同一文件可从缺的那片继续。未完成的上传按 24 小时巡检清理，单用户最多 5 个并发。

- 跨设备已读：在一台设备上读完，同账号其它设备的红点同步消失。服务端
  `POST /message/read` 新增 `clientMsgIds`——同一逻辑消息在各设备上的会话标签不同，
  按标签只能命中自己那份副本，必须按逻辑消息 id 更新；更新后向本人其它设备推
  `100024`（不带内容，只叫它们重拉未读）。

- 多设备同步支持附件引用：同步副本里带媒体 id + 一次性密钥（不搬密文），
  接收设备按 id 取密文解密并缓存，历史里的图片/语音/文件在另一台设备上直接可用；
  密文过期则显示「已过期或被清理」。`SyncE2eTest` 增加 3KB 附件的往返校验。

- 修复窗口「初始太大且拉不小」：`window.minimumSize` 被按 density 换算成物理像素
  （880dp → 1760），比屏幕可用区还宽，窗口被撑到屏幕外且无法缩小。现在按屏幕
  可用区自适应（小屏缩到「屏幕 − 48」），最小值取 880×620 与初始值的较小者。

- 登录页品牌区收进色板 token（`BrandTop/BrandBottom/OnBrand/BrandLine`）：深色下
  整体提亮一档并在接缝处画一条亮线，避免两块深色相邻糊在一起；顺带修掉装饰环
  不裁剪、溢到右侧表单区的问题（浅色纸上能看到几道多余弧线）。

- 补齐媒体释放的第三条路径：登录对账（`GET /message/clear`）丢掉本机缓存时，
  也把这次真正被清掉的、自己上传的媒体密文释放掉。做法是丢弃前后各取一次
  「我上传的媒体 id」，差集才是被删的——清除点之后的消息附件仍然能打开。
  之前这条路径只能靠服务端 30 天保留期兜底。

- 传输与运维补强：客户端拒绝非回环地址的明文 http（`MIM_ALLOW_INSECURE=1`
  显式放行，本机回环仍可 http），地址被拒时停在说明页而不是静默降级；
  新增 `tools/backup.sh`（数据库 + 媒体密文 + 校验和 + 恢复说明，实测 14 表 /
  784 条消息可完整还原）；`forward-headers-strategy` 改为可配置并写清反代终结
  TLS 时 `getScheme()` 恒为 http 会 403 的坑；修正 operations 里 JavaFX 时代的
  客户端 TLS 说明。

- 桌面端外观打磨：新增深色模式（跟随系统，可在「更多」里手动切浅色/深色）；
  强调色与实心块拆成两个 token，深色下不再出现「亮底白字」；对方气泡浅色用极淡投影、
  深色用 1px 边框分层；空态收进统一组件（图标 54/22、说明限宽居中）；
  头部待处理申请角标从数字改为点状，避免压住图标笔画。

- 修复空态文案在 312dp 列表栏里顶到两边缘（两栏改版后空态仍按整窗宽度排版）：
  空态自带 24dp 左右内边距、正文限宽 228dp 并居中，长句手动断成两行，
  图标从 64dp 收到 54dp。

- 新增本机登录保护：同一设备连续 3 次输错登录密码，客户端立即删除该账号在本机的
  全部加密数据（设备标识、密钥包、会话、消息缓存、离线队列）并清掉记住的密码。
  只在设备侧计数与执行，服务端与对方记录不受影响；只统计「账号或密码错误」，
  网络错误/限流/二次验证码错误/自动登录失败都不计入。注册前单独弹框确认，
  并写入用户协议（版本升到 2026-12）。

- 新增媒体保留策略（服务端）与主动释放（客户端）：服务端看不到密文里的媒体
  引用关系，因此清理靠「发送方清除会话 / 撤回时 DELETE /media/{id}」加
  「按 crt_time 的保留期巡检（默认 30 天，`MIM_MEDIA_RETENTION_DAYS` 可配置，
  0 关闭）」。先删文件再删库行，文件删不掉就保留记录等下一轮，不留孤儿文件。
  过期后客户端提示「附件已过期或被清理」，不再把下载失败说成格式问题。

- 部署与运维闭环：修掉 TLS 从来没启动成功的问题（Tomcat 10 只设 Connector 的
  keystore 属性不会创建 SSLHostConfig，8443 起不来）；真跑了一遍 HTTPS/WSS 并记录
  实测结果（TLS1.2/1.3 可用、1.1 被拒、WSS 101、明文 403）；
  新增 `docs/deploy.md` 与 `deploy/`（systemd 单元、Caddy/nginx 示例、env 样例）；
  `MIM_BIND_ADDRESS` 可把应用端口锁到本机，避免代理模式下伪造转发头绕过明文拒绝；
  客户端外观选择改为持久化（`~/.mim/ui.prefs`）。

- 修复小屏下登录页被切掉：窗口在矮屏上最大化后，表单高度超过可视区域，
  登录按钮与错误提示落到窗口外。登录页右侧改为「按窗口高度自适应」：
  矮窗口（<720dp）顶部对齐并整块可滚动，高窗口仍垂直居中。

- 桌面端体验补强：列表支持 `↑`/`↓` + `Enter` 键盘选择，`Esc` 在二级页面返回；
  图标按钮 / 会话行 / 头像 / 发送按钮补无障碍名称与角色；
  修掉高 DPI 下最小窗口尺寸写死物理像素导致两栏挤塌的问题。

- 新增头像上传 / 移除（可选）：客户端选图后重编码成 PNG（丢掉 EXIF）再上传，
  服务端只接受 PNG、≤512KB，仅本人与联系人可读；不设置时仍是首字母头像。
  头像**不是端到端加密**的（要给联系人看），这一点写进了 README 与安全文档。

- 新增找回邮箱的绑定 / 更换 / 解绑（`POST /user/email`，需当前密码）：
  注册时邮箱可选，之前只能靠重注册；现在安全设置里能补上，解绑会明确提示
  「之后无法用邮箱找回密码」。服务端按账号加密存储，只回给账号本人。

- 新增任意文件附件：与图片/语音同一条端到端媒体链路（一次性密钥 AES-GCM 加密
  后上传密文，服务端只见密文与大小），单文件 ≤8MB；收到后是文件卡片，
  点一下解密并另存，状态分「解密中 / 已保存 / 保存失败」。

- 修复「双方清除」对离线设备不生效：原来只靠推送清对方本地缓存，对方不在线
  （或重装后）重新登录仍能看到被抹掉的内容。新增 `GET /message/clear?peers=`，
  客户端登录 / 刷新列表 / 打开会话时按清除点丢弃本机更早的缓存；
  缓存条目开始记录服务端消息 id，旧条目按清除时间兜底。
