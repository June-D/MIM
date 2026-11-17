package com.junesoon.mim.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 把消息渲染成聊天窗口的 HTML 片段。
 *
 * <p>输出结构与 chat.css 约定一致：
 * <pre>
 * &lt;div class="message-item [right] [recalled]" data-id="..."&gt;
 *   &lt;div class="meta"&gt;&lt;span class="label-name"&gt;我&lt;/span&gt;&lt;span class="timestamp"&gt;12:30&lt;/span&gt;&lt;/div&gt;
 *   &lt;div class="bubble"&gt;正文&lt;/div&gt;
 * &lt;/div&gt;
 * </pre>
 *
 * <p>所有文本都经过转义，避免消息内容注入 HTML/JS。
 */
public class HtmlUtil {

    /**
     * @param messageKey 消息的稳定标识（clientMsgId），用于撤回时定位
     * @param labelName  展示名
     * @param timestamp  时间戳
     * @param content    消息正文（撤回时传占位文本）
     * @param incoming   是否对方发来的消息
     * @param recalled   是否已撤回
     */
    public static String of(String messageKey, String labelName, long timestamp, String content,
        boolean incoming, boolean recalled) {
        boolean mine = !incoming;
        // 当天只显示时分，跨天带日期，减少无信息量的重复
        String time = new SimpleDateFormat(isToday(timestamp) ? "HH:mm" : "MM/dd HH:mm")
            .format(new Date(timestamp));

        String idAttr = messageKey == null ? "" : " data-id='" + escapeAttr(messageKey) + "'";
        StringBuilder sb = new StringBuilder(256);
        sb.append("<div class=\"message-item").append(mine ? " right" : "")
            .append(recalled ? " recalled" : "").append('"').append(idAttr).append('>');
        sb.append("<div class=\"meta\">");
        sb.append("<span class=\"label-name\">").append(escape(labelName)).append("</span>");
        sb.append("<span class=\"timestamp\">").append(time).append("</span>");
        sb.append("</div>");
        sb.append("<div class=\"bubble\">").append(escape(content)).append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 兼容旧签名。
     */
    public static String of(String labelName, long timestamp, String content, boolean incoming) {
        return of(null, labelName, timestamp, content, incoming, false);
    }

    private static boolean isToday(long timestamp) {
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd");
        return day.format(new Date(timestamp)).equals(day.format(new Date()));
    }

    /**
     * 属性值转义：只保留安全字符，避免 clientMsgId 破坏 HTML 结构。
     */
    private static String escapeAttr(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String escape(String content) {
        if (content == null) {
            return "";
        }
        // @formatter:off
        return content.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
            .replace("\"", "&quot;");
        // @formatter:on
    }
}
