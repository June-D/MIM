/*
 * C ABI 冒烟测试。
 *
 * 直接链接 Rust 静态库，跑完整会话：
 *   A(Rust FFI) 建会话 → 加密 → B(Rust FFI) 解密 → B 回信 → A 解密
 *   再验证状态持久化：保存 B → 释放 → 恢复 → 继续解密。
 *
 * 这段代码就是 Android JNI 与 iOS cinterop 的最小调用样例。
 */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "mim_core.h"

/* 固定测试密钥（DER 十六进制，与 Rust/Java 基准向量同源） */
static const char *A_IDENTITY_PRIV =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    "ed1aaac79c7a0a07e89016ca9fd088faecb674bfdb364c61772b304dc47656b0a14403"
    "420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444"
    "383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
static const char *A_IDENTITY_PUB =
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004bd83be1776a29249"
    "7bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c2347"
    "00ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
static const char *A_EPHEMERAL_PRIV =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    "db9918aa00a325c358939f01c7657587656f46c8bf97f05d84a396ca933f3df0a14403"
    "4200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9"
    "cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
static const char *A_EPHEMERAL_PUB =
    "3059301306072a8648ce3d020106082a8648ce3d030107034200044f0d04460e020585"
    "e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c829"
    "70129e14b517a669a73a01000b0842ba12d9aa95e7";
static const char *B_IDENTITY_PRIV =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    "6a3364f2700f5aab8d9e81f56c3776a167fcd66f2c62299274afb64e9ad531dea14403"
    "420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cf"
    "ee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
static const char *B_IDENTITY_PUB =
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf4"
    "4684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f4"
    "0fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
static const char *B_SIGNED_PRE_KEY_PRIV =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    "938517594ecaf3587286695e0e675f1177c0278b6281f3517c83ace4ab8bf0e6a14403"
    "420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1"
    "403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
static const char *B_SIGNED_PRE_KEY_PUB =
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c718"
    "8a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f02593638"
    "64b3c70c382cc840e5a6040f762b5ac5ec75e28013";
static const char *B_ONE_TIME_PRE_KEY_PRIV =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    "c998cc833a3669a48ad6de76655b06aa568dc152a73517675afe94da3bf444d5a14403"
    "4200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443"
    "dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";
static const char *B_ONE_TIME_PRE_KEY_PUB =
    "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e"
    "035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c"
    "82c80ea19569a32cddbf66ed6a3c700c38aecd5836";

static const char *SPK_SIGNATURE =
    "MEUCIQD4MX39ZTDckhzL6Ql1gSGQQjaUQzzcQ2ta/ILSLHoMOAIgSbLzDswGAXWGEodmtHBxzmGRP8JEamuGXG4Vl7piwqA=";

typedef struct {
    uint8_t *data;
    size_t len;
} Bytes;

static Bytes unhex(const char *hex) {
    size_t n = strlen(hex) / 2;
    Bytes out = {malloc(n), n};
    for (size_t i = 0; i < n; i++) {
        unsigned int value = 0;
        sscanf(hex + i * 2, "%2x", &value);
        out.data[i] = (uint8_t) value;
    }
    return out;
}

static void expect_text(const char *actual, size_t actual_len, const char *expected) {
    if (actual_len != strlen(expected) || memcmp(actual, expected, actual_len) != 0) {
        fprintf(stderr, "正文不符：得到 %.*s，期望 %s\n", (int) actual_len, actual, expected);
        exit(1);
    }
}

int main(void) {
    printf("mim_version: %s\n", mim_version());

    Bytes a_ik_priv = unhex(A_IDENTITY_PRIV);
    Bytes a_ik_pub = unhex(A_IDENTITY_PUB);
    Bytes a_ek_priv = unhex(A_EPHEMERAL_PRIV);
    Bytes a_ek_pub = unhex(A_EPHEMERAL_PUB);
    Bytes b_ik_priv = unhex(B_IDENTITY_PRIV);
    Bytes b_ik_pub = unhex(B_IDENTITY_PUB);
    Bytes b_spk_priv = unhex(B_SIGNED_PRE_KEY_PRIV);
    Bytes b_spk_pub = unhex(B_SIGNED_PRE_KEY_PUB);
    Bytes b_opk_priv = unhex(B_ONE_TIME_PRE_KEY_PRIV);
    Bytes b_opk_pub = unhex(B_ONE_TIME_PRE_KEY_PUB);

    int32_t err = -1;
    MimSession *a = mim_initiator_session(
        a_ik_priv.data, a_ik_priv.len, a_ik_pub.data, a_ik_pub.len,
        b_ik_pub.data, b_ik_pub.len, b_spk_pub.data, b_spk_pub.len,
        b_opk_pub.data, b_opk_pub.len, a_ek_priv.data, a_ek_priv.len,
        a_ek_pub.data, a_ek_pub.len, &err);
    assert(err == MIM_OK && a != NULL);

    uint8_t *keys_json = NULL;
    size_t keys_len = 0;
    assert(mim_initiator_pubkeys(a, &keys_json, &keys_len) == MIM_OK);
    printf("发起方参数: %.*s\n", (int) keys_len, (char *) keys_json);
    mim_bytes_free(keys_json, keys_len);

    uint8_t iv[12];
    memset(iv, 0x40, sizeof(iv));
    uint8_t *wire = NULL;
    size_t wire_len = 0;
    const char *first = "hello from C";
    assert(mim_encrypt(a, iv, sizeof(iv), (const uint8_t *) first, strlen(first),
                       &wire, &wire_len) == MIM_OK);
    printf("首条消息: %.*s\n", (int) wire_len, (char *) wire);

    MimSession *b = mim_responder_session(
        b_ik_priv.data, b_ik_priv.len, b_ik_pub.data, b_ik_pub.len,
        b_spk_priv.data, b_spk_priv.len, b_spk_pub.data, b_spk_pub.len,
        b_opk_priv.data, b_opk_priv.len, a_ik_pub.data, a_ik_pub.len,
        a_ek_pub.data, a_ek_pub.len, 1, &err);
    assert(err == MIM_OK && b != NULL);

    uint8_t *plain = NULL;
    size_t plain_len = 0;
    assert(mim_decrypt(b, wire, wire_len, &plain, &plain_len) == MIM_OK);
    expect_text((const char *) plain, plain_len, first);
    mim_bytes_free(plain, plain_len);
    printf("B 解密首条消息成功\n");

    /* 重复投递必须失败（跳键/链已推进） */
    assert(mim_decrypt(b, wire, wire_len, &plain, &plain_len) != MIM_OK);
    printf("重复投递被拒绝\n");

    memset(iv, 0x50, sizeof(iv));
    const char *reply = "hello from C reply";
    uint8_t *wire2 = NULL;
    size_t wire2_len = 0;
    assert(mim_encrypt(b, iv, sizeof(iv), (const uint8_t *) reply, strlen(reply),
                       &wire2, &wire2_len) == MIM_OK);

    assert(mim_decrypt(a, wire2, wire2_len, &plain, &plain_len) == MIM_OK);
    expect_text((const char *) plain, plain_len, reply);
    mim_bytes_free(plain, plain_len);
    printf("A 解密回信成功\n");

    /* 状态持久化：B 保存 → 释放 → 恢复 → 继续解密 A 的新消息 */
    uint8_t *state_json = NULL;
    size_t state_len = 0;
    assert(mim_session_save(b, &state_json, &state_len) == MIM_OK);
    printf("B 状态快照 %zu 字节\n", state_len);

    /* 加密落盘：密钥来自平台密钥库，这里用随机密钥演示。 */
    uint8_t state_key[32];
    uint8_t state_nonce[12];
    assert(mim_random_bytes(state_key, sizeof(state_key)) == MIM_OK);
    assert(mim_random_bytes(state_nonce, sizeof(state_nonce)) == MIM_OK);
    uint8_t *sealed = NULL;
    size_t sealed_len = 0;
    assert(mim_state_encrypt(state_json, state_len, state_key, sizeof(state_key),
                             state_nonce, sizeof(state_nonce), &sealed, &sealed_len) == MIM_OK);
    uint8_t *opened = NULL;
    size_t opened_len = 0;
    assert(mim_state_decrypt(sealed, sealed_len, state_key, sizeof(state_key),
                             &opened, &opened_len) == MIM_OK);
    if (opened_len != state_len || memcmp(opened, state_json, state_len) != 0) {
        fprintf(stderr, "状态加解密往返不一致\n");
        return 1;
    }
    mim_bytes_free(opened, opened_len);
    state_key[0] ^= 0xff;
    assert(mim_state_decrypt(sealed, sealed_len, state_key, sizeof(state_key),
                             &opened, &opened_len) != MIM_OK);
    printf("状态加解密与错误密钥拒绝通过\n");
    mim_bytes_free(sealed, sealed_len);

    mim_session_free(b);

    b = mim_session_load(state_json, state_len, &err);
    assert(err == MIM_OK && b != NULL);
    mim_bytes_free(state_json, state_len);

    memset(iv, 0x60, sizeof(iv));
    const char *third = "after restore";
    uint8_t *wire3 = NULL;
    size_t wire3_len = 0;
    assert(mim_encrypt(a, iv, sizeof(iv), (const uint8_t *) third, strlen(third),
                       &wire3, &wire3_len) == MIM_OK);
    assert(mim_decrypt(b, wire3, wire3_len, &plain, &plain_len) == MIM_OK);
    expect_text((const char *) plain, plain_len, third);
    mim_bytes_free(plain, plain_len);
    printf("恢复状态后继续解密成功\n");

    /* 设备密钥管理 */
    uint8_t *bundle_json = NULL;
    size_t bundle_len = 0;
    assert(mim_key_bundle_generate(1, 4, &bundle_json, &bundle_len) == MIM_OK);
    assert(bundle_len > 100);
    printf("密钥包 %zu 字节\n", bundle_len);
    mim_bytes_free(bundle_json, bundle_len);

    uint8_t spk_sig[256];
    size_t spk_sig_len = strlen(SPK_SIGNATURE);
    memcpy(spk_sig, SPK_SIGNATURE, spk_sig_len);
    int32_t spk_ok = mim_signed_pre_key_verify(
        a_ik_pub.data, a_ik_pub.len, 1,
        b_spk_pub.data, b_spk_pub.len, spk_sig, spk_sig_len);
    assert(spk_ok == MIM_OK);
    assert(mim_signed_pre_key_verify(
        a_ik_pub.data, a_ik_pub.len, 2,
        b_spk_pub.data, b_spk_pub.len, spk_sig, spk_sig_len) != MIM_OK);
    printf("签名预密钥验签与篡改拒绝通过\n");

    mim_bytes_free(wire, wire_len);
    mim_bytes_free(wire2, wire2_len);
    mim_bytes_free(wire3, wire3_len);
    mim_session_free(a);
    mim_session_free(b);
    free(a_ik_priv.data); free(a_ik_pub.data);
    free(a_ek_priv.data); free(a_ek_pub.data);
    free(b_ik_priv.data); free(b_ik_pub.data);
    free(b_spk_priv.data); free(b_spk_pub.data);
    free(b_opk_priv.data); free(b_opk_pub.data);

    printf("FFI_OK\n");
    return 0;
}
