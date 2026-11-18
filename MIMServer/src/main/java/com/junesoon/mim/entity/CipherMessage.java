package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 服务器只保存/转发密文，不持有任何解密密钥。
 *
 * <p>双棘轮消息附带 {@code ratchetPublic} / {@code previousChainLength} /
 * {@code messageNumber} 三个头字段。它们本身是公开信息（不含密钥），
 * 接收方靠它们判断是否需要推进 DH 棘轮、以及从哪条链取消息密钥。
 */
@Data
public class CipherMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 客户端生成的唯一 ID，用于幂等与发送状态回执。
     */
    private String clientMsgId;

    private String fromUsername;

    /**
     * 发信设备。多设备下用于区分同一用户的多个设备。
     */
    private String fromDeviceId;

    private String toUsername;

    /**
     * 这条密文的目标设备。给多设备用户发消息时，每个设备一份密文。
     */
    private String targetDeviceId;

    /**
     * 加密算法标识，例如 X3DH-P256-DOUBLE-RATCHET-AES-256-GCM。
     */
    private String alg;

    /**
     * Base64(12 字节随机 IV)。
     */
    private String iv;

    /**
     * Base64(AES-GCM 密文，包含 16 字节 tag)。
     */
    private String ciphertext;

    // ---------------- 双棘轮消息头 ----------------

    /**
     * Base64 的棘轮公钥。对端据此判断是否需要推进 DH 棘轮。
     */
    private String ratchetPublic;

    /**
     * 上一次发送链的长度。对端切换棘轮时用来跳过旧链剩余密钥。
     */
    private Integer previousChainLength;

    /**
     * 本条消息在当前发送链上的序号。
     */
    private Integer messageNumber;

    // ---------------- X3DH 握手参数（仅首条消息携带） ----------------

    /**
     * 发信人的身份公钥。接收方用它比对 TOFU 固定值并完成 X3DH。
     * 服务端只透传，不做任何校验（验签在客户端）。
     */
    private String senderIdentityKey;

    /**
     * 发信人的临时公钥。
     */
    private String senderEphemeralKey;

    /**
     * 发信人是否使用了收信方的一次性预密钥。
     */
    private Boolean senderUsedOneTimePreKey;

    /**
     * 被消耗的一次性预密钥编号。
     */
    private Integer consumedOneTimePreKeyId;

    /**
     * 本条握手使用的是接收方哪一把签名预密钥。
     */
    private Integer recipientSignedPreKeyId;

    // ---------------- 兼容字段 ----------------

    /**
     * 旧版本（静态 ECDH）使用的公钥版本号。双棘轮会话不依赖它，
     * 保留字段以便灰度期同时支持两种算法。
     */
    private Integer senderKeyVersion;

    private Integer recipientKeyVersion;

    /**
     * 客户端时间戳（毫秒），参与 AAD 校验。
     */
    private Long clientTime;

    /**
     * 服务器接收时间（毫秒）。
     */
    private Long serverTime;

    /**
     * 明文是否做了长度填充。接收方据此决定是否剥掉填充前缀。
     */
    private Boolean padding;

    private Boolean delivered;

    private Boolean readFlag;

    // ---------------- 撤回 ----------------

    /**
     * 是否已撤回。撤回后服务端清空密文字段，只留元数据。
     */
    private Boolean recalled;

    private Long recalledAt;

    /**
     * 哪台设备把原文重新加密给 {@link #targetDeviceId}。
     * 为空表示这是原始副本；非空时接收方要用与该设备的会话解密，而不是用发信人的会话。
     */
    private String syncFromDevice;

    // ---------------- 设备间同步的来源签名 ----------------

    /**
     * 原消息的发送者、收信人与发送设备。由转发设备签名，接收方验签后以它们为准，
     * 避免服务端篡改 from_user 把历史消息挂到别人名下。
     */
    private String syncOriginalFrom;

    private String syncOriginalTo;

    private String syncOriginalDevice;

    /**
     * 转发设备身份私钥对「原消息归属 + 内容」的签名。
     */
    private String syncSignature;
}
