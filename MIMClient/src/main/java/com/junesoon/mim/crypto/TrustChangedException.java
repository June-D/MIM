package com.junesoon.mim.crypto;

/**
 * 对端身份公钥与本机固定值不一致。
 *
 * <p>这是 TOFU 的核心告警：可能是对方换了设备，也可能是中间人攻击。
 * 与普通的 {@link IdentityException} 区分开，是因为它<b>可以被用户显式接受</b>——
 * 用户线下核对指纹后确认无误，就应当能继续发送，而不是被永久卡死。
 *
 * <p>若把它混同为一般错误，界面只能显示"发送失败"，用户无从处置；
 * 这类"安全机制正确工作但把用户逼进死胡同"的设计同样是缺陷。
 */
public class TrustChangedException extends IdentityException {

    private static final long serialVersionUID = 1L;

    private final String peer;

    private final String previousFingerprint;

    private final String currentFingerprint;

    /**
     * 待接受的新公钥（Base64）。用户确认后用它更新本机固定值。
     */
    private final String newPublicKey;

    public TrustChangedException(String peer, String previousFingerprint,
        String currentFingerprint, String newPublicKey, String message) {
        super(message);
        this.peer = peer;
        this.previousFingerprint = previousFingerprint;
        this.currentFingerprint = currentFingerprint;
        this.newPublicKey = newPublicKey;
    }

    public String getPeer() {
        return peer;
    }

    public String getPreviousFingerprint() {
        return previousFingerprint;
    }

    public String getCurrentFingerprint() {
        return currentFingerprint;
    }

    public String getNewPublicKey() {
        return newPublicKey;
    }
}
