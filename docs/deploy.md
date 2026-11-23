# 从零部署 MIM 服务端

> 目标：一台 Linux 机器 + 一个域名，30 分钟内跑起一个**只能通过 HTTPS 访问**的服务端，
> 并且知道怎么备份、怎么升级、怎么回滚。
>
> 本文所有「已实测」的结论都是在本机 macOS + JDK 17 + MySQL 8 环境里真跑出来的，
> 不是照着配置文档抄的。

## 0. 先选拓扑（二选一，不要混用）

| 拓扑 | 谁终结 TLS | 优点 | 代价 |
|---|---|---|---|
| **A. 反向代理终结**（推荐） | Caddy / nginx | 证书自动续期、可加 WAF、多实例好扩 | 要配 `MIM_FORWARD_HEADERS=framework`，且必须把应用端口锁到本机 |
| B. 应用内 TLS | Tomcat（8443） | 不依赖代理，单进程 | 证书续期要自己重启，端口多一个 |

两种都支持；`deploy/` 目录里有 `Caddyfile`、`nginx.conf`、`mim-server.service`、
`mim.env.example` 可直接改。

## 1. 依赖

- JDK 17（服务端与 jar 都在 17 上验证；更高版本未测）
- MySQL 8（用了 `ON DUPLICATE KEY UPDATE`、`SELECT ... FOR UPDATE`，不兼容 H2）
- Redis 7（限流、在线状态、WS 路由）
- 可选：Caddy 2 或 nginx 1.24+

```bash
useradd --system --home /var/lib/mim --shell /usr/sbin/nologin mim
mkdir -p /opt/mim /var/lib/mim/media /var/lib/mim/avatar /etc/mim
chown -R mim:mim /var/lib/mim
```

## 2. 建库

```bash
mysql -uroot -e "CREATE DATABASE mim_core CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -uroot -e "CREATE USER 'mim'@'127.0.0.1' IDENTIFIED BY '<强密码>'; \
  GRANT ALL ON mim_core.* TO 'mim'@'127.0.0.1';"
mysql -uroot mim_core < MIMServer/sql/chat.sql
mysql -uroot mim_core < MIMServer/sql/e2ee.sql
```

其余表（`t_device`、`t_media`、`t_avatar`、`t_conversation_clear`、`t_friend_request`、
一次性恢复码等）由服务端启动时自动补齐；旧库升级也一样，不用手工跑 ALTER。

## 3. 配置

```bash
cp deploy/mim.env.example /etc/mim/mim.env
chmod 600 /etc/mim/mim.env      # 里面有主密钥
```

必改项（文件里用 `*` 标了）：`MIM_DB_PASSWORD`、`MIM_REDIS_PASSWORD`、
`MIM_MASTER_KEY`、`MIM_EMAIL_KEY`。

```bash
openssl rand -base64 32   # 生成 32 字节密钥，两把分别生成，不要复用
```

> 两把密钥丢了会怎样：`MIM_MASTER_KEY` 丢失 → **服务端拒绝启动**（库里的 JWT 私钥解不开）；
> `MIM_EMAIL_KEY` 丢失 → TOTP 与找回邮箱的密文解不开。它们**不在**数据库备份里，必须单独保管。

### 拓扑 A：反向代理终结 TLS

```bash
MIM_BIND_ADDRESS=127.0.0.1      # 只让本机代理连 8080
MIM_FORWARD_HEADERS=framework   # 认 X-Forwarded-Proto
MIM_TLS_ENABLED=false
```

代理侧见 `deploy/Caddyfile` 或 `deploy/nginx.conf`。两条硬要求：

1. 代理必须带 `X-Forwarded-Proto: https`；
2. **8080 只能对本机开放**——否则外部客户端可以直连 8080 并自带
   `X-Forwarded-Proto: https` 绕过明文拒绝。这条不是理论风险，本文档实测过：
   同一台机器 `curl -H 'X-Forwarded-Proto: https' http://127.0.0.1:8080/user/info`
   会从 403 变成 401，也就是「被当成安全连接放行」了。

### 拓扑 B：应用内 TLS

```bash
MIM_TLS_ENABLED=true
MIM_TLS_PORT=8443
MIM_TLS_KEYSTORE=/etc/mim/tls/mim-tls.p12
MIM_TLS_KEYSTORE_PASSWORD=<口令>
```

自签证书（仅测试；生产用 Let's Encrypt 或企业 CA）：

```bash
keytool -genkeypair -alias mim -keyalg RSA -keysize 2048 \
  -dname "CN=mim.example.com" -validity 365 -storetype PKCS12 \
  -keystore /etc/mim/tls/mim-tls.p12 -storepass <口令> -keypass <口令> \
  -ext "SAN=dns:mim.example.com"
```

开 TLS 后：8443 走 HTTPS，8080 仍在监听但**所有业务请求返回 403**（有意的，
防止绕过 TLS 抓 JWT）。协议固定 TLS 1.2/1.3。

## 4. 启动与自检

```bash
systemctl daemon-reload
systemctl enable --now mim-server
journalctl -u mim-server -f
```

日志里应当看到：

- `TLS 已启用：https://0.0.0.0:8443（keystore=…，协议=TLSv1.2/TLSv1.3）`（拓扑 B）
- `Tomcat started on ports 8080 (http), 8443 (https)`
- `安全自检：…` 若干条 WARN——这些是「生产环境应改」的提醒，逐条处理：
  Redis 密码、`MIM_EMAIL_KEY`、`MIM_MASTER_KEY`、设备绑定开关。

## 5. 验证（照着敲，别只看日志）

```bash
# 1) 明文端口应被拒绝（拓扑 B 或有代理时）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/user/info      # 期望 403

# 2) HTTPS 应返回 401（接口正常、只是没带令牌）
curl -s -o /dev/null -w '%{http_code}\n' https://mim.example.com/user/info    # 期望 401

# 3) TLS 版本：1.2 / 1.3 可用，1.0/1.1 被拒
openssl s_client -connect mim.example.com:443 -tls1_3 </dev/null 2>/dev/null | grep Protocol
openssl s_client -connect mim.example.com:443 -tls1_1 </dev/null 2>&1 | tail -2

# 4) WebSocket 升级（需要有效令牌）
TOKEN=$(curl -s -X POST https://mim.example.com/login/token \
  -H 'Content-Type: application/json' -H 'X-Device-Id: probe' \
  -d '{"username":"<账号>","password":"<密码>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"])')
curl -s -i -N --max-time 3 -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Authorization: Bearer $TOKEN" -H "X-Device-Id: probe" \
  https://mim.example.com/websocket/compose | head -3     # 期望 HTTP/1.1 101
```

2026-11-23 在本机实测结果（应用内 TLS）：

| 检查 | 结果 |
|---|---|
| `http://127.0.0.1:8080/user/info` | `403`（明文被拒） |
| `https://localhost:8443/user/info`（带 CA 校验） | `401`（接口正常） |
| `openssl -tls1_3` / `-tls1_2` | 均协商成功（`AEAD-CHACHA20-POLY1305` / `ECDHE-RSA-…`） |
| `-tls1_1` | 被拒绝 |
| WSS 升级 | `HTTP/1.1 101 Switching Protocols` |
| 代理模式加伪造转发头 | `403` → `401`（**证明必须锁 8080**） |

## 6. 客户端指向

```bash
MIM_SERVER_URL=https://mim.example.com MIMCompose/run-desktop.sh
```

客户端**拒绝非回环地址的明文 http**。自签 CA 需要让 JVM 信任：

```bash
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/path/to/trust.p12 \
  -Djavax.net.ssl.trustStorePassword=<口令>"
```

## 7. 备份与恢复

```bash
tools/backup.sh /var/backups/mim          # 数据库 + 媒体 + 校验和 + RESTORE.md
```

恢复演练（建议每月一次，在隔离环境做）：

1. `sha256sum -c SHA256SUMS`；
2. 导入 `db.sql`；
3. 解开 `media.tar.gz` 到 `MIM_MEDIA_DIR` 的上一级；
4. **用同一把 `MIM_MASTER_KEY` / `MIM_EMAIL_KEY` 启动**；
5. 登录 → 发一条消息 → 收一条 → 打开一条旧附件 → 销毁演练环境。

头像（`MIM_AVATAR_DIR`）也可一并归档：丢了只会回到首字母头像，但没法从库还原。
媒体与头像的**密文/文件**都在磁盘上，别忘了备份策略要覆盖这两个目录。

## 8. 升级与回滚

```bash
# 升级
tools/backup.sh /var/backups/mim          # 1) 先备份
systemctl stop mim-server                 # 2) 停服
cp MIMServer-<新版本>-exec.jar /opt/mim/mim-server.jar.new && \
  mv /opt/mim/mim-server.jar.new /opt/mim/mim-server.jar   # 原子替换
systemctl start mim-server                # 3) 起服务
curl -s -o /dev/null -w '%{http_code}\n' https://mim.example.com/user/info   # 4) 期望 401

# 回滚：把上一版 jar 换回去，再启动；数据库结构由启动时自动补表，向前兼容
```

滚动窗口内旧客户端仍可用：协议只在明确标注「Phase 2」的能力上做开关，
默认关闭；升级服务端不会让已在用的客户端失联。

## 9. 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| 所有请求 403 | 开了 TLS 但客户端走 http；或代理没带 `X-Forwarded-Proto` | 客户端改 https；代理加转发头 + `MIM_FORWARD_HEADERS=framework` |
| 8443 起不来，日志 `No SSLHostConfig element was found` | Tomcat 10 只给 Connector 设 keystore 属性不会建 SSLHostConfig | 已修（`TlsConfig` 用 `SSLHostConfig` + `SSLHostConfigCertificate`）；确认用的是新版本 |
| 客户端报证书不受信 | 自签 CA 未导入 JVM truststore | 见第 6 节 |
| 启动即退出，日志提到 encrypted JWT key | 库里有加密私钥但没配 `MIM_MASTER_KEY` | 补上正确的 `MIM_MASTER_KEY`，不要尝试「换个新钥」 |
| WS 一分钟断一次 | 代理或网关的读超时太短 | `proxy_read_timeout 3600s` / Caddy `read_timeout 1h` |
| 找回密码提示「邮件服务未配置」 | 没配 SMTP（有意的降级） | 见 `operations.md` 的 SMTP 一节 |

## 10. 已知边界

- 服务端**不保存消息明文**，但也因此无法「帮用户恢复历史」：设备丢了、密钥没了，历史就没了；
- 媒体密文有保留期（默认 30 天），过期后附件不可下载（客户端会明确提示）；
- 头像是可选公开资料，**不是端到端加密**的（服务端可见），不要用真实照片；
- 单机部署足够小团队使用；多实例只需共享 MySQL + Redis，会话与密钥都在客户端。
