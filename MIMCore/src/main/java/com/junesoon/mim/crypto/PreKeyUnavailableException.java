package com.junesoon.mim.crypto;

/**
 * 对端某台设备尚未发布预密钥包。
 *
 * <p>与签名/信任失败区分开：设备可能刚注册、预密钥还没上传完成，
 * 这时应当跳过该设备继续发给其它设备，而不是让整条消息发送失败。
 */
public class PreKeyUnavailableException extends IdentityException {

    private static final long serialVersionUID = 1L;

    public PreKeyUnavailableException(String message) {
        super(message);
    }
}
