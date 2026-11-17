package com.junesoon.mim.model;

import lombok.Data;

/**
 * 客户端视角的密文消息。
 */
@Data
public class CipherMessage {

    private Long id;

    /**
     * 客户端生成的唯一 ID，用于幂等与发送状态回执。
     */
    private String clientMsgId;

    private String fromUsername;

    /**
     * 发信设备（客户端视角，通常不需要读，服务端以请求头为准）。
     */
    private String fromDeviceId;

    private String toUsername;

    /**
     * 这条密文的目标设备。
     */
    private String targetDeviceId;

    private String alg;

    /**
     * Base64(12 字节随机 IV)。撤回后为空。
     */
    private String iv;

    /**
     * Base64(AES-256-GCM 密文，包含 16 字节 tag)。撤回后为空。
     */
    private String ciphertext;

    // ---------------- 双棘轮消息头 ----------------

    /**
     * Base64 棘轮公钥。
     */
    private String ratchetPublic;

    /**
     * 上一次发送链长度。
     */
    private Integer previousChainLength;

    /**
     * 当前发送链上的消息序号。
     */
    private Integer messageNumber;

    // ---------------- X3DH 握手参数（仅首条消息携带） ----------------

    /**
     * 发信人的身份公钥（Base64）。接收方用它校验 TOFU 与完成 X3DH。
     */
    private String senderIdentityKey;

    /**
     * 发信人的临时公钥（Base64）。每条新会话唯一，提供初始前向安全性。
     */
    private String senderEphemeralKey;

    /**
     * 发信人是否使用了接收方的一次性预密钥。
     */
    private Boolean senderUsedOneTimePreKey;

    /**
     * 被消耗的一次性预密钥编号。
     */
    private Integer consumedOneTimePreKeyId;

    /**
     * 本条握手使用的是接收方哪一把签名预密钥。接收方据此找回对应私钥，
     * 避免签名预密钥轮换后，轮换前发出的首条消息永久解不开。
     */
    private Integer recipientSignedPreKeyId;

    // ---------------- 兼容字段 ----------------

    private Integer senderKeyVersion;

    private Integer recipientKeyVersion;

    private Long clientTime;

    private Long serverTime;

    private Boolean delivered;

    private Boolean readFlag;

    // ---------------- 撤回 ----------------

    private Boolean recalled;

    private Long recalledAt;

    /**
     * 哪台自己的设备把原文重新加密过来的。非空时用与该设备的会话解密。
     */
    private String syncFromDevice;

    // ---------------- 设备间同步的来源签名 ----------------

    /**
     * 原消息的发送者。与下面的字段一起由转发设备签名，不能相信服务端给的 fromUsername。
     */
    private String syncOriginalFrom;

    private String syncOriginalTo;

    private String syncOriginalDevice;

    /**
     * 转发设备身份私钥对「原消息归属 + 内容」的签名。
     */
    private String syncSignature;
}
