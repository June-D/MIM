# 运行、部署与运维

> 从 README 拆出：多实例、限流、TLS、建库、启动、密钥轮换、客户端运行与自测。

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

- **JDK 17**：服务端、协议核心与 PC 客户端都用它
- Rust 工具链（cargo）：安全内核与动态库
- Maven Wrapper（`./mvnw`）固定 **Maven 3.9.9**；也可使用系统 Maven 3.6.3+
- MySQL 8 / Redis 5+


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

> 也可以直接跑 `MIMServer/run-server.sh`：打包、复制到 target 之外、等待就绪。
> 复制这一步是必要的：`verify.sh` 会在 MIMServer 下执行 `clean`，
> 正在被 JVM 读取的 `target/*.jar` 被删掉会让运行中的服务端抛
> `NoSuchFileException`，表现为登录突然 500。`--stop` 可停止已有实例。

> 全仓库只需要 JDK 17；Maven 已由 `mvnw` 固定为 3.9.9。
> 协议核心以 source/target 1.8 编译，但运行在 17 上，不再需要额外的 JDK 8 与 JavaFX。

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

### 启动 PC 客户端

```bash
MIMCompose/run-desktop.sh          # 真实服务端 + 自动构建 JNI 动态库
MIMCompose/run-desktop.sh --check  # 只检查环境，不打开窗口
```

保险库在 `~/.mim/<用户名>/<设备>/`：密钥包、会话状态、消息缓存、离线队列、
屏蔽名单全部 AES-GCM 加密，密钥文件 0600。

### 自测

```bash
# 一键（推荐）
./verify.sh

# 或分端执行（在各自目录）
cd MIMServer && ../mvnw test      # 210 项（含真实 MySQL 集成）
cd MIMCore   && ../mvnw test      # 129 项：HKDF/SecretBox、X3DH+双棘轮、信封、盲签名
cd MIMCoreRust && cargo test      # 19 项：原语/ECDH/X3DH/握手/双棘轮/信封/证书验签/状态加密/密钥管理
cd MIMCoreRust && ./tools/interop.sh   # Rust 发起 → Java 解密回信 → Rust 解密（真实互通）
cd MIMCoreRust && ./tools/ffi_test.sh  # C 程序经 C ABI 跑完整会话与状态恢复
cd MIMCoreRust && ./tools/jni_test.sh  # 桌面 JVM 经 JNI 跑完整会话（Android 同款）


# PC 客户端（Compose；首次构建会下载 Gradle 与 Compose 依赖）
cd MIMCompose && ./gradlew :composeApp:run            # 演示后端
MIMCompose/run-desktop.sh                             # 真实服务端 + Rust 动态库
MIMCompose/run-desktop.sh --check                     # 只检查环境
cd MIMCompose && ./gradlew :composeApp:desktopTest    # 状态测试；联调需 MIM_SERVER_URL

# Rust 动态库必须带 jni 特性，否则 Kotlin 找不到 JNI 符号
cd MIMCoreRust && cargo build --release --features jni
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

端到端脚本位于 `MIMCore/src/test/e2e`（只依赖 mim-core 与 JDK 自带 HTTP）。
需要先起 MySQL/Redis/服务端，然后：

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
