# HTTP 接口与 WebSocket 通知

> 从 README 拆出。契约以服务端 controller 为准，这里是快速索引。

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

## 加密媒体

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/media` | 上传密文；`Content-Type: application/octet-stream`，返回 `{id, size}` |
| GET | `/media/{id}` | 下载密文；需要登录，id 为 32 字节随机数 |
| DELETE | `/media/{id}` | 仅上传者可删；删除后下载返回「媒体不存在」 |

服务端不保存文件名、媒体类型或缩略图——这些都在端到端密文里。
单文件上限由 `mim.media.max-bytes` 控制（默认 18MB 密文，对应约 8MB 明文）。
