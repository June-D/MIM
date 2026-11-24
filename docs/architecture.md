# 架构与加密

## 模块

| 目录 | 职责 |
|---|---|
| `MIMServer/` | Spring Boot 3 / Java 17：设备、公钥、预密钥、密文、好友、媒体、WSS |
| `MIMCoreRust/` | 密码学核心（X3DH、Double Ratchet、状态加密），导出 C ABI |
| `MIMiOS/` `MIMKit/` | iOS 客户端与 SDK（SwiftUI，SPM） |
| `MIMCompose/` | 桌面端（Compose Multiplatform，Kotlin） |
| `tools/` | 备份等运维脚本 |

服务端数据只有元数据与密文：

```
t_device          设备（device_id 维度，含 revoked / token_version / push_registration）
t_user_key        每设备的身份公钥 + 指纹
t_signed_prekey   签名预密钥（身份私钥签名，客户端验签后才用）
t_one_time_prekey 一次性预密钥（预留 → 使用后删除，10 分钟未用自动释放）
t_message         每行是「一份密文副本」：(to_user, target_device_id, client_msg_id, 密文)
```

## 密钥体系

每个**设备**一套独立密钥：身份密钥（Ed25519/X25519）、签名预密钥、一批一次性预密钥、
独立的棘轮状态。给某人发消息时按「他的所有有效设备」各加密一份。

服务端另有几把与消息无关的密钥：

| 密钥 | 用途 | 存放 |
|---|---|---|
| RSA 私钥（`t_rsa_key`） | 签发 JWT / 发送者证书 | 数据库（可选落盘加密） |
| `MIM_MASTER_KEY` | 加密 TOTP 种子等敏感字段 | `~/.mim/keys/master.key` |
| `MIM_EMAIL_KEY` | 加密找回邮箱 | `~/.mim/keys/email.key` |

> 未配置后两者时，主密钥会**回落到 `SHA-256(RSA 私钥)`**——RSA 私钥一旦重新生成
> （例如数据库被清），已存的 TOTP / 邮箱密文就永久解不开，登录却报「验证码错误」。
> 所以这两个文件必须持久化，且与数据库一起备份。

## 加密

**会话建立（X3DH）**

```
DH1 = DH(IK_A, SPK_B)   DH2 = DH(EK_A, IK_B)
DH3 = DH(EK_A, SPK_B)   DH4 = DH(EK_A, OPK_B)（可能省略）
SK  = HKDF(DH1‖DH2‖DH3‖DH4, "MIM/v2/initial", 32)
```

签名预密钥由对方身份私钥签名，客户端用**已固定**的身份公钥验签——服务端被攻破也
伪造不了预密钥包。

**棘轮**

```
KDF_RK(rk, dh) → newRK = HKDF-Expand(prk,"MIM/v2/root",32)
                 newCK = HKDF-Expand(prk,"MIM/v2/chain",32)
KDF_CK(ck)     → mk = HMAC(ck,0x01)（本条密钥，用完即弃）
                 nextCk = HMAC(ck,0x03)
```

**附件**：一次性 32 字节密钥 + 12 字节 nonce，AES-256-GCM（`MIM-STATE-1` 文本封装）。
上传的是密文，密钥只写在消息正文里（随消息端到端加密）。

**发送者身份**：sealed sender——服务端不存 `from`，发送者把「发送者证书」封进密文里
自证身份；接收方用发送者的身份公钥验签。

## 安全边界

服务端能看到：谁在收、发给哪台设备、密文长度、时间。
服务端看不到：消息内容、文件内容与文件名、会话双方是谁、谁发的。

代价（诚实记录）：

- **搜索只能在本机做**——服务端没有明文；
- **推送正文只能是「你有新消息」**——它不知道是谁发的（最多带一个随机会话标签）；
- **历史搬迁有上限**（当前 30 天 / 50 条），因为搬的是重新加密的副本而非明文。

## 一次发送的完整路径

```
发送方：领预密钥 → 逐设备建/推进棘轮 → 生成 N 份密文（同一 clientMsgId）
      → POST /message → 本地存明文（加密落盘）
服务端：落库 → WSS 推给目标设备（离线则极光推送）→ 等 /message/delivered 确认
接收方：拉取 → 解密 → 验签 → 入库 → 回执（已读再推 100012）
```
