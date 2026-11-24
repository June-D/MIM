# 协议与接口

统一响应信封（少数接口例外，见「坑」）：`{status, message, data, rel}`，`status=200` 表示成功，
其余为业务错误码。所有请求带 `Authorization: Bearer <jwt>` 与 `X-Device-Id`。

## HTTP

| 分类 | 接口 |
|---|---|
| 账号 | `POST /register/token`、`POST /login/token`、`POST /logout` |
| 设备 | `POST /device`（登记，幂等）、`GET /device`（列表）、`DELETE /device/{id}`（注销）、`POST /device/push`（上报极光 registrationId） |
| 密钥 | `GET /key/{user}`（各设备公钥）、`POST /key`（上传本机公钥，**需密码**） |
| 预密钥 | `GET /prekey/{user}`（领取，含预留）、`POST /prekey`（补充） |
| 消息 | `POST /message`（提交密文）、`GET /message/undelivered`、`POST /message/delivered`、`POST /message/read`、`POST /message/recall/{clientMsgId}`（**必须带 tags**）、`POST /message/clear` |
| 同步 | `POST /message/sync/request`、`POST /message/sync` |
| 好友 | `GET /chat/contacts`、`GET /chat/search?keyword=`、`POST /chat/requests`、`GET /chat/requests`、`POST /chat/requests/{id}/accept|reject`、`DELETE /chat/contacts/{peer}`、`POST /chat/contacts/{peer}/label` |
| 媒体 | `POST /media`（≤8MB 直传）、`GET /media/{id}`、`/media/upload/init|{id}/{n}|complete`（分片） |
| 安全 | `GET /user/info`、`POST /user/password`、`POST /user/email`、`GET/POST /user/2fa/{status,setup,enable,disable,recovery-codes}`、`POST /recover/email`、`POST /recover/reset` |
| 证书 | `GET /cert/sender`（发送者证书）、`GET /cert/server`（服务端公钥） |

## WebSocket

`wss://host:8443/websocket/compose`，认证走 header（不放 URL，URL 会进日志）。
25 秒无帧发 `{"ping":true}`。推送只是「提醒」，数据一律走 HTTP 拉取。

| 类型 | 含义 |
|---|---|
| `100010` | 新消息（附带一份密文，可直接用，也可忽略后自己拉） |
| `100012` | 已读回执（`{peer, beforeId}`） |
| `100014` | 撤回 |
| `100016/100017/100018` | 好友申请 / 同意 / 拒绝 |
| `100019` | 设备被注销（客户端应立刻退出） |
| `100022` | 另一台设备请求历史同步 |
| `100024` | 另一台设备已读 |

> 写错编号不会报错，只会「静默不生效」。`PushEventTests` 把编号钉死了。

## 密文与信封

一条逻辑消息 → N 份密文（每个目标设备一份），共享同一个 `clientMsgId`：

```json
{"clientMsgId":"…","tag":"<会话标签>","targetDeviceId":"…",
 "ciphertext":"<base64>","iv":"<base64 12B>","senderCert":"<base64>","senderToken":"…"}
```

- `tag`：会话标签，双方共享的随机串。撤回、清除、同步都靠它定位;
- `senderToken`：盲签名令牌，服务端据此匿名提交，不记录 from。

**媒体消息正文**（作为密文内容传输，服务端不可见）：

```json
{"v":1,"kind":"image|file|voice","text":"","media":{"id":"…","key":"<base64 32B>","size":123,"mime":"image/png","name":"a.png","durationMs":1500}}
```

**同步副本**（`SyncPayload`，源设备身份私钥签名）：

```
canonical = clientMsgId|tag|from|to|device
```

服务端按 `client_msg_id + conversation_tag` 找原消息，校验后复制成新副本给新设备。

## 坑（都实际踩过）

1. `GET /chat/contacts` **直接返回数组**，不套信封——用通用 JSON 信封解析会失败；
2. 一条逻辑消息在**不同接收设备上的 tag 各不相同**，同步/撤回用错就「没有可同步的原消息」；
3. 撤回必须带 `tags`，否则 `40001 缺少会话标签`；
4. 上传公钥要**当前密码**（防「只偷到 JWT 就换身份」）；
5. 设备必须在 `t_device` 里且未注销，否则发消息回「设备未注册或已注销」（换账号复用了旧设备 id 时最易踩）。
