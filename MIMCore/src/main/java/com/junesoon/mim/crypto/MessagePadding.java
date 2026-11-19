package com.junesoon.mim.crypto;

/**
 * 消息长度填充：把明文补到 256 字符的固定桶，隐藏精确长度。
 *
 * <p>只有双方都声明支持时才会使用；填充后的明文带一个前缀，接收方按前缀里的
 * 原始长度截断。旧客户端不会收到填充消息，新客户端收到未填充消息时原样展示。
 */
public final class MessagePadding {

    private static final String MAGIC = "MIMPAD1:";
    private static final int BLOCK = 256;

    private MessagePadding() {
    }

    public static String pad(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String prefix = MAGIC + plaintext.length() + ":";
        int base = prefix.length() + plaintext.length();
        int target = ((base + BLOCK - 1) / BLOCK) * BLOCK;
        if (target == base) {
            target += BLOCK;
        }
        StringBuilder sb = new StringBuilder(target);
        sb.append(prefix).append(plaintext);
        while (sb.length() < target) {
            sb.append(' ');
        }
        return sb.toString();
    }

    public static String strip(String value) {
        if (value == null || !value.startsWith(MAGIC)) {
            return value;
        }
        int colon = value.indexOf(':', MAGIC.length());
        if (colon < 0) {
            return value;
        }
        try {
            int length = Integer.parseInt(value.substring(MAGIC.length(), colon));
            int start = colon + 1;
            if (length < 0 || start + length > value.length()) {
                return value;
            }
            return value.substring(start, start + length);
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
