package com.junesoon.mim.crypto;

/**
 * 轻量「会话标签信封」。
 *
 * <p>问题背景：会话标签由客户端生成，只在「会话未确认」时随证书信封传一次。
 * 如果双方各自生成过标签，或证书信封那条消息没送达，另一方就永远不知道
 * 这个标签，带该标签的消息在历史里直接消失。
 *
 * <p>因此每条已确认会话的消息都再包一层标签信封（开销约 40 字节）：
 * 接收端解密后总能学到发送方使用的标签，双方最终收敛到同一组标签。
 *
 * <p>格式：{@code U+0001mimtag1U+0001 + tag + '\n' + 正文}。
 * 标签为空或格式不合法时原样返回，绝不影响正文。
 *
 * [INPUT]: 只依赖 JDK
 * [OUTPUT]: pack / unpack / isEnvelope
 * [POS]: MessageService 发送端打包、解密端拆包；服务端永远看不到标签语义
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class TagEnvelope {

    private static final String MAGIC = "\u0001mimtag1\u0001";

    private static final char BODY_SEP = '\n';

    private TagEnvelope() {
    }

    /**
     * 打包标签与正文；标签为空时原样返回正文。
     */
    public static String pack(String tag, String payload) {
        if (payload == null || tag == null || tag.isEmpty()) {
            return payload;
        }
        return MAGIC + tag + BODY_SEP + payload;
    }

    public static boolean isEnvelope(String text) {
        return text != null && text.startsWith(MAGIC);
    }

    /**
     * 拆包；不是信封或结构不合法时返回 null，调用方应把原串当正文。
     */
    public static Parsed unpack(String text) {
        if (!isEnvelope(text)) {
            return null;
        }
        int sep = text.indexOf(BODY_SEP, MAGIC.length());
        if (sep < 0) {
            return null;
        }
        String tag = text.substring(MAGIC.length(), sep);
        if (tag.isEmpty() || tag.length() > 64) {
            return null;
        }
        return new Parsed(tag, text.substring(sep + 1));
    }

    /**
     * 拆包结果。
     */
    public static final class Parsed {
        private final String tag;
        private final String text;

        public Parsed(String tag, String text) {
            this.tag = tag;
            this.text = text;
        }

        public String getTag() {
            return tag;
        }

        public String getText() {
            return text;
        }
    }
}
