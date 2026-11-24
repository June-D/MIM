# iOS 客户端要用的接口地图

> 目的：Xcode 装好之前，先把「Swift 侧要调什么、调完是第几步」定下来。
> 全部内容来自现有代码，不是设想——C ABI 的每一条都能在 `MIMCoreRust/include/mim_core.h`
> 找到，HTTP 每一条都能在 `docs/server-api.md` 找到。

## 1. 结论：密码学这层已经够用

`libmim_core_rust` 现在导出 **22 个 `mim_*` 符号**，其中首条消息（X3DH）与身份签名
在 2026-11 补齐——之前只有 JNI 有，iOS 拿不到。补齐后 iOS 不需要 JNI，
也不需要为了「能跑起来」重写任何密码学。

验证方式（不需要 Xcode）：`cargo test --test ffi_abi_test`，4 项用例直接调 C ABI：

```
first_message_round_trips_through_c_abi ... ok
identity_signature_round_trips_through_c_abi ... ok
garbage_input_returns_error_codes ... ok
key_bundle_generation_works_through_c_abi ... ok
```

## 2. Swift 桥接已实测跑通

`tools/swift_abi_smoke.sh` 用 macOS 自带的 `swiftc` 直接调这些 ABI 函数：

```
编译 Swift 冒烟程序…
· 密钥包已生成（各 1 个一次性预密钥）
· 首条消息已组装：tag=gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E
· 对端解出明文：来自 Swift 的首条消息
· 签名长度 96 字节（Base64 文本）
· 签名往返正常，篡改被拒
· 非法输入返回错误码而非崩溃
SWIFT_ABI_OK
```

这段 Swift 就是 iOS 侧要写的桥接层（`tools/swift/abi_smoke.swift`）：
头文件导入、指针传递、`mim_bytes_free` 归还、JSON 字段名，一个都没绕开。
iOS 上只是换 target 与链接方式。

## 3. Swift 封装已落地：`MIMiOS/MIMKit`

桥接层不再是示例代码，已经是一个可编译的 Swift Package：

| 文件 | 内容 |
|---|---|
| `Sources/MIMKit/MimCore.swift` | C ABI 封装：`makeKeyBundle` / `composeFirstMessage` / `openFirstMessage` / `sign` / `verify` |
| `Sources/MIMKit/Models.swift` | 与 `src/messaging.rs` 逐字对应的 DTO |
| `Sources/MIMKit/Keychain.swift` | Keychain 读写（`AfterFirstUnlockThisDeviceOnly`）+ `deleteAll()` |
| `Sources/MIMKit/MIMClient.swift` | 登录 / 设备列表 / 注销；统一信封与错误码（40102、40001） |
| `Sources/MIMKitSmoke/` | 不依赖 XCTest 的冒烟程序，`swift run MIMKitSmoke` |

冒烟输出（macOS，无需 Xcode）：

```
MIMKit 冒烟（core mim-core-rust 0.1.0）
· 生成 alice 密钥包
· 组装首条消息（tag 一致）
· 对端解出明文：iOS 第一条消息
· 证书归属还原为 alice-iphone
· 身份签名往返
· 篡改后验签失败
MIMKIT_SMOKE_OK
```

## 4. C ABI 清单（Swift 侧 `import MIMCoreABI`）

| 函数 | 用途 | iOS 何时调 |
|---|---|---|
| `mim_key_bundle_generate(version, opk_count, ...)` | 生成身份密钥 + 签名预密钥 + 一次性预密钥（JSON） | 首次登录，上传到 `/key/bundle` |
| `mim_signed_pre_key_verify(...)` | 校验对端签名预密钥 | 收到对端 bundle 后 |
| `mim_compose_first_message(request_json)` | 组装首条消息（X3DH + 证书信封 + 双棘轮） | 与该对端的第一条消息 |
| `mim_open_first_message(request_json)` | 拆首条消息，拿回明文与会话状态 | 收到对端第一条消息 |
| `mim_initiator_session` / `mim_responder_session` | 老接口：直接持会话句柄 | 暂不需要（首条消息接口已封装） |
| `mim_encrypt` / `mim_decrypt` | 会话内加解密（句柄式） | 同上，走 `*_sealed` 更省事 |
| `mim_compose_sealed` / `mim_open_sealed` | 会话内加解密（JSON 进 JSON 出，带会话状态） | 后续每条消息 |
| `mim_session_store_insert` | 把会话状态并入存储 JSON（按 peer + device + tag 索引） | 首条消息之后 |
| `mim_session_save` / `mim_session_load` | 会话状态落盘/读取 | 冷启动恢复 |
| `mim_initiator_pubkeys` | 导出首条消息要携带的公开参数 | 首条消息组装时内部用 |
| `mim_state_encrypt` / `mim_state_decrypt` | 用 Keychain 里的 32 字节密钥加密本地状态 | 本地保险库 |
| `mim_sign_with_identity` / `mim_verify_with_identity` | 身份签名/验签（多设备同步副本归属） | 同步历史时 |
| `mim_random_bytes` / `mim_bytes_free` / `mim_session_free` / `mim_version` | 随机数、释放、版本 | 基础设施 |

约定（头文件里写明，Swift 侧要遵守）：

- 所有 `const uint8_t *` 输入都带长度，不要求 NUL 结尾；
- 输出缓冲由库分配，**必须** `mim_bytes_free` 归还；
- 返回 0 成功，非 0 是 `MIM_ERR_*`；**参数错与密码学失败分开**（1 vs 2/4/5），不要都当「失败」处理；
- 消息与状态都是 UTF-8 JSON，加字段不破坏 ABI。

### 文本收发已用真服务端验证（M2 的协议核心）

```bash
cd MIMiOS/MIMKit && swift run MIMKitSmoke --chat http://127.0.0.1:8080
# · 两个账号已注册并登录（swiftchat-a-…/swiftchat-b-…）
# · 双方密钥已发布
# · A 已投递到 1 台设备
# · B 解出：你好，来自 Swift …
# · B 识别出是从 swiftchat-a-… 发来的
# · A 解出回信：收到，这是回信 …
# · 会话索引非空（1 条）
# CHAT_SMOKE_OK
```

覆盖 X3DH 首条消息（含发送者证书）、双棘轮回信、多设备副本、会话存储往返。

### 与真服务端联调已验证（M1 的 HTTP 路径）

```bash
cd MIMiOS/MIMKit && swift run MIMKitSmoke --login http://127.0.0.1:8080 composealice <密码>
# · 登录成功（composealice / swift-smoke-…）
# · 密钥包已发布（身份 + 签名预密钥 + 5 个一次性预密钥）
# · 设备列表包含本机（共 N 台）
# · 已注销本次冒烟注册的设备
# LIVE_SMOKE_OK
```

这条命令不依赖 Xcode/模拟器，是 iOS 侧与后端的第一条真实链路验证。

**踩过的两个坑（Swift 侧必看）**：

1. **命名风格不是统一的**：Rust DTO 是 `snake_case`，服务端 DTO 是 `camelCase`。
   `MIMClient` 不能对请求套 `.convertToSnakeCase`，否则 `deviceId` 变成 `device_id`，
   服务端回「设备标识不能为空」。MimCore（Rust 层）才用 snake_case。
2. **`GET /prekey/{username}` 即使带 `deviceId` 也返回列表**，要按 deviceId 取那一份；
3. **公钥编码三层不一致**：服务端是 Base64(SPKI)，Rust 要 hex，`alg` 只认
   `X3DH-P256-DOUBLE-RATCHET-AES-256-GCM`。把 Base64 当 hex 传会得到
   `handshake` 失败；`alg` 写错会被服务端拒为「不支持的加密算法」。
4. **会话存储的形状**：Rust 的 `SessionStore` 是 `{"sessions": []}`，且
   `StateSnapshot.skipped` 是必填数组（没有 serde default）。少给一项就
   `invalidArgument`——这个错误不带任何细节，只能靠对照结构体排查。
5. **查询串必须单独设置**：把 `?deviceId=…` 塞进 `URLComponents.path` 会被转义成
   `%3F`，服务端收到的用户名变成 `xxx?deviceId=yyy`，报「用户名不合法」。
6. **`GET /message/undelivered` 不会自动置已投递**：必须再调
   `POST /message/delivered?beforeId=<最大 id>`。漏了这一步的表现很隐蔽——
   消息不丢，但每轮轮询都会再收到一次，界面重复、时间戳不停刷新。
   另外 `beforeId` 是**查询参数**，不是请求体。
7. **已读回执的类型是 `100012`，不是 `100011`**：写错不会报错，只是「消息到了但
   永远显示未读」。`PushEventTests` 把这几个编号钉死了。
8. **媒体载荷不能用前缀判断**：消息正文是 `{"v":1,"kind":"image","media":{…}}`，
   但 JSON 键顺序不保证（Swift 的 JSONEncoder 会把 `text` 排到最前）。按
   `{"v":1,"kind":` 匹配会漏，必须按解码结果 + `kind` 白名单判断。
9. **撤回必须带 `tags`**：服务端要靠标签定位密文，`tags: []` 会返回
   `40001 缺少会话标签`。iOS 的标签在会话索引里，按对端聚合后一起传。
10. **`data` 的形状不统一**：`POST /device` 返回对象、`POST /key` 返回对象、
   `POST /prekey/one-time` 返回数字、某些接口是 null。只关心 `status` 的请求
   用 `MIMEnvelope<IgnoredPayload>` 吞掉 `data`，别为类型匹配反复改结构体。

## 5. HTTP 接口（按 M1→M3 的调用顺序）

| 阶段 | 接口 | 说明 |
|---|---|---|
| M1 登录 | `POST /login/token` | 账号密码 + 设备名 + 可选二次验证码；返回 JWT |
| 推送 | `POST /device/push` | 上报极光 registrationId（只登记当前令牌绑定的设备） |
| M1 发钥 | `POST /device` | 登记设备（`{deviceId, deviceName}`），响应 data 是对象 |
| | `POST /key` | 身份公钥（**Base64 SPKI**）+ `password` + 能力位 |
| | `POST /prekey/signed` | `{preKeyId, publicKey, signature}` |
| | `POST /prekey/one-time` | `[{preKeyId, publicKey}]` |
| | `GET /user/info` | 校验令牌是否还活着 |
| | `POST /key/bundle` / `GET /key/{username}` | 上传/拉取预密钥包 |
| | `GET /user/devices` / `DELETE /user/device/{id}` | 设备列表与注销 |
| M2 文本 | `POST /message` | 发送密文副本（**路径是 `/message`，不是 `/message/send`**） |
| | `GET /message/undelivered` | 拉未投递（**拉取不会置已投递**） |
| | `POST /message/delivered?beforeId=` | 确认收到；不调用会重复下发同一条 |
| | `GET /message/history/{peer}?tags=…` | 历史 |
| | `POST /cert/sender` | 为本设备签发发送者证书（`/cert` 是 404） |
| | `POST /message/read` | 已读（带 `clientMsgIds` 才能跨设备副本） |
| | `POST /message/recall/{clientMsgId}` | 撤回；body `{recallToken, tags}`——**缺 tags 会 400** |
| M2 同步 | `POST /message/sync/request` | 请其它设备转发历史（服务端推 100022） |
| | `POST /message/sync` | 提交同步副本；服务端按 `client_msg_id + 标签`找原消息 |
| | `POST /message/clear` | 清除会话；`{peer, forBoth, tags}`，iOS 只做单方 |
| | `GET /message/clear` | 清除点对账 |
| | `WS /websocket/{deviceId}` | 推送（见第 4 节） |
| M3 媒体 | `POST /media` | ≤8MB 单请求上传密文（octet-stream），返回 `{id,size}` |
| | `POST /media/upload/init` + `PUT .../{index}` + `POST .../complete` | >8MB 分片（64KB~8MB 一片） |
| | `GET /media/{id}` | 下载密文 |
| | `DELETE /media/{id}` | 清除/撤回后释放 |
| 其它 | `GET /user/avatar/{username}` / `POST /user/avatar` | 头像（非端到端加密） |
| | `POST /message/sync` / `POST /message/sync/request` | 多设备历史同步 |

## 6. WebSocket 推送类型

| 类型 | 含义 | iOS 侧动作 |
|---|---|---|
| `100010` | 新消息 | 拉增量 → 解密 → 入库 + 本地通知 |
| `100012` | 已读回执（payload `{peer, beforeId}`） | 在我发出的消息下标「已读」 |
| `100014` | 撤回 | 替换为「已撤回」 |
| `100019` | 对方清除会话 | 清本地缓存 + 释放自己上传的密文 |
| `100022` | 另一台设备请求历史 | 弹确认框（显示设备 id + 指纹） |
| `100024` | 另一台设备已读 | 清该会话红点 + 重拉未读 |

## 7. 三条时序（照这个顺序写，少走弯路）

1. **首次登录**：`login/token` → `key_bundle_generate` → `POST /key/bundle` →
   Keychain 存私钥 → 若本机无历史则 `POST /message/sync/request`。
2. **发消息**（已与会话）：`compose_sealed` → `POST /message/send` →
   `session_store_insert` → 本地入库（带 `syncedFrom = null`）。
3. **收消息**（首条与会话内分开）：`GET /message/history` →
   `syncFromDevice == null` 时，若 `sessionId` 未知走 `open_first_message`，
   否则走 `open_sealed` → 验签/归属 → 入库。

## 8. iOS 侧要自己实现的部分

- **Keychain**：身份私钥、设备标识、本地状态密钥（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`）；
- **网络**：`URLSession` + `URLSessionWebSocketTask`（wss）；
- **JSON**：与 `src/messaging.rs` 的 DTO 字段逐字对齐（可在 Rust 侧生成夹具防漂移）；
- **分片上传**：与服务端一致的长度校验（末片是余数）；
- **后台推送**：APNs（需 Apple 开发者账号签发 `.p8`，服务端要加通道；MVP 可先不做）。

## 9. 已知缺口（诚实清单）

| 缺口 | 影响 | 什么时候补 |
|---|---|---|
| APNs 服务端通道 | 后台收不到推送（前台 WSS 正常） | M4 |
| C ABI 无「批量补一次性预密钥」 | 预密钥耗尽后需重新生成整包 | M2 之后再评估 |
| 本地状态加密依赖调用方提供 nonce | iOS 侧必须每次新生成（12 字节），否则复用 nonce 会毁掉 GCM 安全性 | M1 就注意 |

> 第 9 节第 3 条不是小问题：`mim_state_encrypt` 把 nonce 交给调用方，
> 是为了让平台密钥不出 Keychain；代价是**每次加密都必须换新 nonce**。
> Swift 侧建议把它封装成「只能通过 `mim_random_bytes` 取 nonce」的 API，
> 从类型上避免手写常量。
