package com.junesoon.mim.model;

import lombok.Data;

/**
 * 预密钥包（客户端视角，按设备）。
 */
@Data
public class PreKeyBundle {

    private String username;

    /**
     * 目标设备。发起方要为每个设备各建一个会话。
     */
    private String deviceId;

    /**
     * 该设备的身份公钥（Base64 X.509）。
     */
    private String identityPublicKey;

    private Integer identityKeyVersion;

    private Integer signedPreKeyId;

    private String signedPreKeyPublic;

    /**
     * 由该设备身份私钥生成的 ECDSA 签名，必须用已固定的身份公钥验证。
     */
    private String signedPreKeySignature;

    /**
     * 一次性预密钥编号，可能为 null（已耗尽）。
     */
    private Integer oneTimePreKeyId;

    private String oneTimePreKeyPublic;

    /**
     * 该设备能否接收带加密撤回凭证的同步副本。
     * 旧客户端没有该字段，按 false 处理，只收纯文本副本。
     */
    private Boolean supportsRecallToken;

    public boolean hasOneTimePreKey() {
        return oneTimePreKeyId != null && oneTimePreKeyPublic != null;
    }

    /**
     * 缓存/查找用的复合键。
     */
    public String key() {
        return username + "@" + deviceId;
    }
}
