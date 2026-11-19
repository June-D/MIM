package com.junesoon.mim.entity;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * 客户端生成的会话标签（不透明，24~64 位 Base64URL 字符）。
     * 服务端只按它建索引，不落任何可从用户名推导的形式；
     * 属于服务端索引，不回传给客户端（旧客户端会因未知字段反序列化失败）。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String conversationTag;

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

    /**
     * 已建立会话后是否使用盲引用隐藏明文发信人/设备。
     */
    private Boolean sealed;

    /**
     * sealed 会话 ID。收件端用它索引对应的双棘轮状态，无需依赖发信人字段。
     */
    private String sessionId;

    /**
     * 仅用于历史接口返回给发送端：这条逻辑消息是否由当前查看者发出。
     * 不入库，也不从客户端请求读取。
     */
    private Boolean fromMe;

    private Boolean delivered;

    private Boolean readFlag;

    // ---------------- 撤回 ----------------

    /**
     * 是否已撤回。撤回后服务端清空密文字段，只留元数据。
     */
    private Boolean recalled;

    private Long recalledAt;

    /**
     * 客户端提交的一次性撤回凭证。只用于请求，绝不入库、绝不回显。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String recallToken;

    /**
     * 撤回凭证的 SHA-256 十六进制。入库用于授权校验，响应中必须隐藏。
     */
    @JsonIgnore
    private String recallTokenHash;

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
