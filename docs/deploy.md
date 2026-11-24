# 部署与运维

## 一条命令

```bash
./start.sh            # MySQL(13306) → Redis(6379) → 服务端(TLS 8443)
./start.sh --status
./start.sh --stop
```

已在运行的组件会跳过，不会重复启动。单独用 `MIMServer/run-server.sh` 也可以（同样读下面的路径）。

## 目录

```
~/MIM-data/mysql     MySQL 数据
~/MIM-data/tls       TLS 证书（mim-tls.p12，App 按指纹锁定它）
~/MIM-data/media     媒体密文（只有密文）
~/MIM-data/logs      server.log / mysql.err
~/.mim/keys          master.key / email.key（派生密钥，丢失等于数据报废）
~/.mim/smtp.env      邮件通道凭据（阿里云 DirectMail）
~/.mim/push.env      极光推送凭据
```

**这六处都必须持久化**。放 `/tmp` 曾在重启后丢过数据库文件：`t_rsa_key` 重建 → RSA 私钥变化
→ 所有用主密钥加密的 TOTP / 邮箱密文全部解不开。

## 必要配置

```bash
# ~/.mim/keys（各 32 字节 Base64，生成一次即可，之后不要动）
openssl rand -base64 32 > ~/.mim/keys/master.key
openssl rand -base64 32 > ~/.mim/keys/email.key
chmod 600 ~/.mim/keys/*

# ~/.mim/smtp.env（阿里云邮件推送）
MIM_SMTP_HOST=smtpdm.aliyun.com
MIM_SMTP_PORT=465
MIM_SMTP_SSL=true
MIM_SMTP_USERNAME=<发信地址>
MIM_SMTP_PASSWORD=<SMTP 密码，不是登录密码>
MIM_MAIL_FROM=<发信地址>
MIM_MAIL_FROM_NAME="MIM 安全中心"

# ~/.mim/push.env（极光；不配则静默跳过推送）
MIM_JPUSH_APP_KEY=<AppKey>
MIM_JPUSH_MASTER_SECRET=<Master Secret>
MIM_JPUSH_APNS_PRODUCTION=false
```

> env 文件里**含空格的值要加引号**，否则 `source` 时会被拆成命令执行。

## TLS

服务端自带一张自签证书（`./start.sh` 找不到会提示）。客户端用**证书指纹锁定**，
所以：

- 换证书 / 换 IP → 必须重签并给两端重新分发（iOS 内置在包里，需重装）；
- 长期使用建议配固定域名 + 真证书，可以彻底免掉这一步。

生成一张（CN 填实际访问地址）：

```bash
keytool -genkeypair -alias mim -keyalg RSA -keysize 2048 \
  -dname "CN=<IP或域名>,O=MIM,C=CN" -validity 365 -storetype PKCS12 \
  -keystore ~/MIM-data/tls/mim-tls.p12 -storepass changeit -keypass changeit \
  -ext "SAN=ip:<IP>,dns:localhost,ip:127.0.0.1"
```

## 备份与恢复

```bash
tools/backup.sh /path/to/backups     # 数据库 + 媒体密文 + SHA256SUMS + RESTORE.md
```

备份**不含**密钥文件（`~/.mim/keys`、TLS keystore）——它们要单独离线保管，
否则备份里的邮箱/TOTP 密文再也解不开。

## 日常检查

```bash
./start.sh --status
tail -f ~/MIM-data/logs/server.log
curl -sk https://127.0.0.1:8443/user/info -o /dev/null -w '%{http_code}\n'   # 期望 401
```

## 故障速查

| 现象 | 原因与处理 |
|---|---|
| 「二次验证码错误或已被使用」，怎么输都不对 | 日志有 `解密…验证密钥失败：Tag mismatch` → 主密钥变了。用恢复码登录后重设 2FA，或管理员置 `totp_enabled=0`。根治：`~/.mim/keys` 持久化 |
| 「设备未注册或已注销」 | 该 `(用户名, device_id)` 不在 `t_device` 或已注销。客户端重新登录（会重新登记） |
| 客户端连不上、报证书错误 | 服务端 IP/证书变了，需重签并重装 App |
| 登录 500 / `Tablespace is missing` | 数据库文件丢失。检查 `~/MIM-data/mysql`，必要时按 schema 重建该表 |
| 推送发不出 | `~/.mim/push.env` 没配或 App 未上报 registrationId（真机需 APNs 能力） |
| 邮件发不出 | 未配 `smtp.env`；或发信域名未验证 / 未配 SPF，邮件被判垃圾 |

## 安全建议

- MySQL / Redis 目前无密码且只监听本机，**对外开放前必须加认证**；
- `MIM_AUTH_REQUIRE_DEVICE_BINDING=true` 可禁止未绑定设备的旧令牌（所有客户端都升级后可开）；
- 媒体与日志目录没有自动清理，长期运行需配轮转或定时清理。
