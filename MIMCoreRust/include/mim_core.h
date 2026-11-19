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

void mim_session_free(MimSession *session);
void mim_bytes_free(uint8_t *ptr, size_t len);
const char *mim_version(void);

#ifdef __cplusplus
}
#endif

#endif /* MIM_CORE_H */
