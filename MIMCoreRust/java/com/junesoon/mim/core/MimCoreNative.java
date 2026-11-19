package com.junesoon.mim.core;

/**
 * Rust 协议核心的 JNI 绑定（Android 与桌面 JVM 共用）。
 *
 * <p>方法直接对应 {@code MIMCoreRust/include/mim_core.h}：
 * 握手返回不透明句柄（long），消息与状态用 JSON 字节数组交换。
 * 失败一律抛 {@link IllegalStateException}，不会返回半成品状态。
 *
 * <p>句柄由调用方负责 {@link #free(long)}；它持有会话私钥，
 * 泄露相当于泄露会话密钥。
 */
public final class MimCoreNative {

    static {
        // 桌面调试可指定绝对路径；Android 走 jniLibs 的默认查找。
        String explicit = System.getProperty("mim.native.lib");
        if (explicit == null || explicit.isEmpty()) {
            explicit = System.getenv("MIM_NATIVE_LIB");
        }
        if (explicit != null && !explicit.isEmpty()) {
            System.load(explicit);
        } else {
            System.loadLibrary("mim_core_rust");
        }
    }

    private MimCoreNative() {
    }

    /**
     * 发起方建会话。
     *
     * @param peerOneTimePreKeyPub 可为 null 或空数组，表示不使用一次性预密钥
     * @return 会话句柄
     */
    public static native long initiatorSession(
        byte[] selfIdentityPriv,
        byte[] selfIdentityPub,
        byte[] peerIdentityPub,
        byte[] peerSignedPreKeyPub,
        byte[] peerOneTimePreKeyPub,
        byte[] ephemeralPriv,
        byte[] ephemeralPub);

    /** 接收方建会话。 */
    public static native long responderSession(
        byte[] selfIdentityPriv,
        byte[] selfIdentityPub,
        byte[] selfSignedPreKeyPriv,
        byte[] selfSignedPreKeyPub,
        byte[] selfOneTimePreKeyPriv,
        byte[] peerIdentityPub,
        byte[] peerEphemeralPub,
        boolean usedOneTimePreKey);

    /** 发起方首条消息要携带的参数：{"identity","ephemeral","used_opk"}。 */
    public static native byte[] initiatorPubkeys(long session);

    /** 加密；iv 必须 12 字节。返回 {"dh","pn","n","iv","ciphertext"}。 */
    public static native byte[] encrypt(long session, byte[] iv, byte[] plaintext);

    /** 解密同一份线上消息 JSON，返回明文。 */
    public static native byte[] decrypt(long session, byte[] wireJson);

    /** 状态快照（含私钥；必须加密后落盘）。 */
    public static native byte[] save(long session);

    /** 从状态快照恢复。 */
    public static native long load(byte[] stateJson);

    /**
     * 生成设备密钥包（JSON）：身份密钥 + 签名预密钥 + 一次性预密钥。
     *
     * @param version        身份密钥版本；私钥丢失后重新生成时递增
     * @param oneTimeCount   一次性预密钥数量
     */
    public static native byte[] generateKeyBundle(int version, int oneTimeCount);

    /**
     * 验证签名预密钥。
     *
     * @param signature Base64 签名的 UTF-8 字节
     */
    public static native boolean verifySignedPreKey(
        byte[] identityPublic,
        int preKeyId,
        byte[] signedPreKeyPublic,
        byte[] signature);

    /**
     * 加密状态快照。
     *
     * @param key   32 字节密钥（必须由平台 Keystore / Keychain 保管）
     * @param nonce 12 字节，每条状态必须新生成
     */
    public static native byte[] encryptState(byte[] plaintext, byte[] key, byte[] nonce);

    /** 解密状态快照；格式、密钥或密文不对都会抛异常。 */
    public static native byte[] decryptState(byte[] blob, byte[] key);

    /** 安全随机字节（生成 nonce 与本地密钥用）。 */
    public static native byte[] randomBytes(int length);

    /** 释放会话句柄。 */
    public static native void free(long session);
}
