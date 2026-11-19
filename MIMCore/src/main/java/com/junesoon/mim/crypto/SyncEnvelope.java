package com.junesoon.mim.crypto;

import java.util.regex.Pattern;

/**
 * 设备间同步副本里的「撤回凭证信封」。
 *
 * <p>撤回凭证只应保存在发送它的那台设备上。要让同账号的另一台设备也能撤回，
 * 唯一不泄露给服务端的办法是：把凭证塞进<b>已经端到端加密</b>的同步副本密文里，
 * 服务端始终只看到密文。
 *
 * <p>信封只发给声明了 {@code supportsRecallToken} 的设备。旧客户端收到信封会
 * 把整串当正文显示，所以必须由发送端按能力位门控，不能无条件加信封。
 *
 * <p>信封判定刻意严格：必须以 {@code U+0001} 开头的魔数开头、凭证符合
 * Base64 式字符集、且带分隔符。任何一条不满足就当作普通正文原样返回，
 * 避免把用户自己输入的文本误判成信封。
 *
 * [INPUT]: 只依赖 JDK 正则，不接触网络与存储
 * [OUTPUT]: pack / unpack / isEnvelope 三个静态方法
 * [POS]: HistorySync 打包、MessageService 解密后拆包；服务端永远看不到 token
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class SyncEnvelope {

    /** 魔数：U+0001 是控制字符，正常聊天文本里不会出现。 */
    private static final String MAGIC = "\u0001mimsync1\u0001";

    private static final char SEPARATOR = '\n';

    /** 与服务端 RecallToken 的长度上限保持一致。 */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9+/=_-]{16,128}$");

    private SyncEnvelope() {
    }

    /**
     * 把凭证与正文打包成同步副本的明文。凭证为空时原样返回正文，
     * 这样「收到但不是自己发的」消息不会多出信封开销。
     */
    public static String pack(String recallToken, String text) {
        if (text == null) {
            return null;
        }
        if (recallToken == null || recallToken.isEmpty()) {
            return text;
        }
        return MAGIC + recallToken + SEPARATOR + text;
    }

    public static boolean isEnvelope(String payload) {
        return payload != null && payload.startsWith(MAGIC);
    }

    /**
     * 拆包；不是信封或格式不合法时返回 null，调用方应把原串当正文。
     */
    public static Parsed unpack(String payload) {
        if (!isEnvelope(payload)) {
            return null;
        }
        int separator = payload.indexOf(SEPARATOR, MAGIC.length());
        if (separator < 0) {
            return null;
        }
        String token = payload.substring(MAGIC.length(), separator);
        if (!TOKEN_FORMAT.matcher(token).matches()) {
            return null;
        }
        return new Parsed(token, payload.substring(separator + 1));
    }

    /**
     * 拆包结果：凭证 + 真正的正文。
     */
    public static final class Parsed {
        private final String recallToken;
        private final String text;

        public Parsed(String recallToken, String text) {
            this.recallToken = recallToken;
            this.text = text;
        }

        public String getRecallToken() {
            return recallToken;
        }

        public String getText() {
            return text;
        }
    }
}
