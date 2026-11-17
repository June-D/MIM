package com.junesoon.mim.crypto;

/**
 * 和一个对端设备之间的两条棘轮。
 *
 * <p>双方可能在收到对方第一条消息之前各自发起 X3DH。这两条链的共享密钥不同，
 * 不能写进同一个 {@link SessionState}，否则后建立的那条会把先发出去的发送链覆盖掉，
 * 两边都解不开。
 *
 * <ul>
 *   <li>{@code outbound}：我方发起的链，发送时优先用它；</li>
 *   <li>{@code inbound}：对方发起的链，只用来解对方带握手参数的消息。</li>
 * </ul>
 */
public final class PeerCipher {

    /**
     * 用对方消息里的 X3DH 参数建一条尚未提交的应答链。
     * 建链过程不要改已经在用的会话，解密失败时调用方应直接丢掉返回值。
     */
    public interface Establisher {
        SessionState establish() throws IdentityException;
    }

    private final RatchetService ratchet;
    private SessionState outbound;
    private SessionState inbound;

    public PeerCipher(RatchetService ratchet) {
        this.ratchet = ratchet;
    }

    public SessionState getOutbound() {
        return outbound;
    }

    public SessionState getInbound() {
        return inbound;
    }

    public void setOutbound(SessionState outbound) {
        this.outbound = outbound;
    }

    public void setInbound(SessionState inbound) {
        this.inbound = inbound;
    }

    /**
     * 下一条发出去的消息用哪条链。我方发起链优先；还没发起过时，用已经能发送的应答链。
     */
    public SessionState sendingState() {
        if (outbound != null) {
            return outbound;
        }
        if (inbound != null && inbound.getSendChainKey() != null) {
            return inbound;
        }
        return null;
    }

    /**
     * 解密对端发来的一条消息。认证失败时两条链都保持调用前的样子。
     *
     * @param hasHandshake       消息是否带了 X3DH 握手参数
     * @param senderEphemeralKey 对方临时公钥；用来区分「同一条链的下一条」和「一次新的发起」
     * @param establisher        需要新建应答链时调用；不需要建链就传 null
     */
    public String open(boolean hasHandshake, String senderEphemeralKey, RatchetService.Header header,
        String iv, String ciphertext, Establisher establisher) throws IdentityException {
        if (hasHandshake) {
            if (inbound != null && senderEphemeralKey != null
                && senderEphemeralKey.equals(inbound.getHandshakeSenderEphemeralKey())) {
                return ratchet.decrypt(inbound, header, iv, ciphertext);
            }
            // 应答方只用临时公钥认出「还是当初那条链」。旧文件没把这个公钥存下来，
            // 重启后它是空的。对方后续消息仍带着握手头，若直接重新 X3DH，
            // 一次性预密钥已经删了，这条链上的新消息就会全部解不开。
            // 先用现有应答链试解；认证通过就补记公钥，失败再当作一次新的发起。
            if (inbound != null) {
                try {
                    String plaintext = ratchet.decrypt(inbound, header, iv, ciphertext);
                    if (inbound.getHandshakeSenderEphemeralKey() == null
                        && senderEphemeralKey != null) {
                        inbound.setHandshakeSenderEphemeralKey(senderEphemeralKey);
                    }
                    return plaintext;
                } catch (IdentityException ignored) {
                    // 不是这条链上的消息
                }
            }
            if (establisher == null) {
                throw new IdentityException("没有可用会话");
            }
            SessionState fresh = establisher.establish();
            fresh.setInitiator(false);
            // 只记下临时公钥，用来认出同一条发起链。不要写入身份公钥，
            // 否则 hasPendingHandshake 会变成 true，应答方的回复会被误当成新的发起。
            fresh.setHandshakeSenderEphemeralKey(senderEphemeralKey);
            String plaintext = ratchet.decrypt(fresh, header, iv, ciphertext);
            inbound = fresh;
            return plaintext;
        }

        IdentityException last = null;
        if (inbound != null) {
            try {
                return ratchet.decrypt(inbound, header, iv, ciphertext);
            } catch (IdentityException e) {
                last = e;
            }
        }
        if (outbound != null) {
            try {
                return ratchet.decrypt(outbound, header, iv, ciphertext);
            } catch (IdentityException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IdentityException("没有可用会话");
    }
}
