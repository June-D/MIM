/*
 * MiM 协议核心 C ABI。
 *
 * Android 通过 JNI、iOS 通过 cinterop 共享这一层；头文件只暴露不透明句柄、
 * 字节缓冲与错误码，不暴露任何 Rust 类型。
 *
 * 约定：
 * - 所有 const uint8_t* 输入都带长度，不要求 NUL 结尾；
 * - 所有输出缓冲由库分配，调用方用 mim_bytes_free 归还；
 * - 返回 0 表示成功，非 0 见 MIM_ERR_*；
 * - 消息与状态均为 UTF-8 JSON，新增字段不会破坏 ABI。
 */
#ifndef MIM_CORE_H
#define MIM_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MIM_OK 0
#define MIM_ERR_INVALID_ARG 1
#define MIM_ERR_HANDSHAKE 2
#define MIM_ERR_ENCRYPT 3
#define MIM_ERR_DECRYPT 4
#define MIM_ERR_STATE 5

typedef struct MimSession MimSession;

/* 发起方建会话。peer_one_time_pre_key_pub 传 NULL/0 表示不使用一次性预密钥。 */
MimSession *mim_initiator_session(
    const uint8_t *self_identity_priv, size_t self_identity_priv_len,
    const uint8_t *self_identity_pub, size_t self_identity_pub_len,
    const uint8_t *peer_identity_pub, size_t peer_identity_pub_len,
    const uint8_t *peer_signed_pre_key_pub, size_t peer_signed_pre_key_pub_len,
    const uint8_t *peer_one_time_pre_key_pub, size_t peer_one_time_pre_key_pub_len,
    const uint8_t *ephemeral_priv, size_t ephemeral_priv_len,
    const uint8_t *ephemeral_pub, size_t ephemeral_pub_len,
    int32_t *out_error);

/* 接收方建会话。 */
MimSession *mim_responder_session(
    const uint8_t *self_identity_priv, size_t self_identity_priv_len,
    const uint8_t *self_identity_pub, size_t self_identity_pub_len,
    const uint8_t *self_signed_pre_key_priv, size_t self_signed_pre_key_priv_len,
    const uint8_t *self_signed_pre_key_pub, size_t self_signed_pre_key_pub_len,
    const uint8_t *self_one_time_pre_key_priv, size_t self_one_time_pre_key_priv_len,
    const uint8_t *peer_identity_pub, size_t peer_identity_pub_len,
    const uint8_t *peer_ephemeral_pub, size_t peer_ephemeral_pub_len,
    int32_t used_one_time_pre_key,
    int32_t *out_error);

/* 导出发起方首条消息要携带的参数：{"identity","ephemeral","used_opk"}。 */
int32_t mim_initiator_pubkeys(const MimSession *session,
                              uint8_t **out_json, size_t *out_len);

/* 加密，输出 {"dh","pn","n","iv","ciphertext"}；iv 必须 12 字节。 */
int32_t mim_encrypt(MimSession *session,
                    const uint8_t *iv, size_t iv_len,
                    const uint8_t *plaintext, size_t plaintext_len,
                    uint8_t **out_json, size_t *out_len);

/* 解密，输入同一份 JSON；输出明文。 */
int32_t mim_decrypt(MimSession *session,
                    const uint8_t *wire_json, size_t wire_json_len,
                    uint8_t **out_plaintext, size_t *out_len);

/* 状态快照（JSON，含私钥；调用方必须加密后落盘）。 */
int32_t mim_session_save(const MimSession *session,
                         uint8_t **out_json, size_t *out_len);

/* 从快照恢复。 */
MimSession *mim_session_load(const uint8_t *json, size_t json_len,
                             int32_t *out_error);

/*
 * 设备密钥管理：生成密钥包 JSON（身份密钥 + 签名预密钥 + 一次性预密钥），
 * 以及用已固定的身份公钥验证签名预密钥。
 * signature 为 Base64 文本的字节，不必 NUL 结尾。
 */
int32_t mim_key_bundle_generate(uint32_t version, uint32_t one_time_count,
                                uint8_t **out_json, size_t *out_len);

int32_t mim_signed_pre_key_verify(const uint8_t *identity_public, size_t identity_public_len,
                                  uint32_t pre_key_id,
                                  const uint8_t *signed_pre_key_public,
                                  size_t signed_pre_key_public_len,
                                  const uint8_t *signature, size_t signature_len);

/*
 * 加密状态存储：密钥必须来自平台 Keystore / Keychain（32 字节），
 * nonce 必须每次新生成（12 字节）。输出为三行文本：
 *   MIM-STATE-1 / Base64(nonce) / Base64(密文+tag)
 */
int32_t mim_state_encrypt(const uint8_t *plaintext, size_t plaintext_len,
                          const uint8_t *key, size_t key_len,
                          const uint8_t *nonce, size_t nonce_len,
                          uint8_t **out_blob, size_t *out_len);

int32_t mim_state_decrypt(const uint8_t *blob, size_t blob_len,
                          const uint8_t *key, size_t key_len,
                          uint8_t **out_plaintext, size_t *out_len);

/* 用安全随机填充调用方缓冲。 */
int32_t mim_random_bytes(uint8_t *out, size_t len);

void mim_session_free(MimSession *session);
void mim_bytes_free(uint8_t *ptr, size_t len);
const char *mim_version(void);

#ifdef __cplusplus
}
#endif

#endif /* MIM_CORE_H */
