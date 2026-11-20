# 目录结构与模块职责

> 从 README 拆出，随代码结构变化更新。

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
  src/test/java/          210 项测试（单元 + Mapper/HTTP 真实 MySQL 集成）

MIMCoreRust/             Rust 可行性 spike：对拍 + 互通 + C ABI（需要 cargo）
  src/envelope.rs        标签 / 同步副本 / 发送者证书信封（格式与 Java 逐字节一致）
  src/sender_cert.rs     证书规范化串与 SHA256withRSA 验签、keyId 指纹校验
  src/secure_store.rs    状态加密落盘（AES-256-GCM，密钥来自平台密钥库）
  src/keys.rs            设备密钥管理（身份密钥、签名预密钥、一次性预密钥、轮换）
  src/messaging.rs       首条消息组装/拆解（X3DH + 证书信封 + 标签 + 双棘轮）
  src/session_store.rs   会话存储（按 sessionId、标签学习、sealed 收发、加密落盘）
  java/com/junesoon/mim/core/MimCoreNative.java  JNI 绑定（Android 直接复用）
  include/mim_core.h     C ABI 头文件（Android JNI / iOS cinterop 的公共底座）
  tools/interop.sh       Rust ⇄ Java 真实收发联调
  tools/ffi_test.sh      链接 Rust 静态库的 C 冒烟测试
  tools/jni_test.sh      桌面 JVM 经 JNI 跑完整会话（Android 调用形态）
MIMCore/                 协议核心（不依赖 JavaFX/Feign，移动端与 Rust 移植的基准）
  crypto/                 Hkdf、DhKeyUtil、RatchetService(X3DH+双棘轮)、SessionState、
                          SessionStore、PreKeyStore、IdentityStore、SecretBox、TrustStore、
                          SyncEnvelope、SenderCertEnvelope、TagEnvelope、BlindRsaToken
  src/test/java/          129 项协议单测

MIMCompose/             PC 主客户端（Compose Multiplatform，JVM 17）
  composeApp/           登录 / 注册 / 联系人 / 添加 / 聊天 / 设备
  .../state/AppStore   单一状态源：登录态、联系人、消息、屏蔽、推送触发同步
  .../data/SecureBackend 真实协议：设备密钥 → 首条消息 → sealed 收发 → 本地加密缓存
  .../data/LocalVault  保险库：密钥包 / 会话 / 消息 / 队列 / 屏蔽名单
  .../ui               Theme（视觉系统）、Icons、Avatar、各页面
  README.md             运行方式与接入说明

MIMClient/
  crypto/                 DemoBot（联调用的演示客户端，依赖 WebSocket，故留在客户端）
  service/                DeviceIdentity（设备标识）、KeyManager、PreKeyManager、
                          MessageService（按设备收发+棘轮）
  feign/                  Login / Register / Device / Key / PreKey / Message
  websocket/              WSClient（只收推送）
  utils/                  TlsSupport（HTTPS/WSS 信任配置）
  gui/                    Login / Main / Chat / Loading
  src/test/java/          11 项单元测试（其余协议测试在 MIMCore）
  src/test/e2e/           多设备 / sealed / WebSocket 端到端回归（需外部服务，手动运行）
```
