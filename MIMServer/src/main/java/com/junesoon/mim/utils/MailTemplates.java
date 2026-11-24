package com.junesoon.mim.utils;

/**
 * 邮件正文模板。
 *
 * <p>只用内联样式的表格布局：邮件客户端对 {@code <style>}、flex、grid 的支持参差不齐，
 * 用表格是最稳的写法。深色按钮 + 大号验证码是为了在手机通知栏预览里也能一眼看出
 * 「这是验证码」——纯文本那版经常被当成垃圾邮件。
 */
public final class MailTemplates {

    private MailTemplates() {
    }

    /** 纯文本版：不支持 HTML 的客户端、以及部分反垃圾网关需要它。 */
    public static String recoveryText(String code) {
        return "你的 MIM 密码找回验证码是：" + code + "\n\n"
            + "10 分钟内有效，仅可使用一次。\n"
            + "如果这不是你本人操作，请忽略本邮件，你的密码不会改变。\n"
            + "MIM 的任何人都不会向你索要这个验证码。";
    }

    /** 密码找回验证码的 HTML 版。 */
    public static String recoveryHtml(String code) {
        String digits = escape(code);
        return "<!DOCTYPE html>"
            + "<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>MIM 密码找回验证码</title></head>"
            + "<body style=\"margin:0;padding:0;background:#f4f5f7;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#f4f5f7;padding:32px 16px;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"max-width:480px;background:#ffffff;border-radius:16px;"
            + "box-shadow:0 2px 12px rgba(16,24,40,0.06);overflow:hidden;\">"

            // 头部
            + "<tr><td style=\"padding:28px 32px 0;font-family:-apple-system,"
            + "'PingFang SC','Helvetica Neue',Arial,sans-serif;\">"
            + "<div style=\"font-size:13px;letter-spacing:2px;color:#5b6472;\">MIM 安全中心</div>"
            + "<div style=\"font-size:20px;font-weight:600;color:#111827;margin-top:6px;\">"
            + "重置你的登录密码</div>"
            + "</td></tr>"

            // 验证码
            + "<tr><td align=\"center\" style=\"padding:24px 32px;\">"
            + "<div style=\"font-family:ui-monospace,SFMono-Regular,Menlo,monospace;"
            + "font-size:40px;font-weight:700;letter-spacing:10px;color:#111827;"
            + "background:#f1f5f9;border-radius:12px;padding:18px 8px 18px 18px;\">"
            + digits + "</div>"
            + "<div style=\"font-family:-apple-system,'PingFang SC',Arial,sans-serif;"
            + "font-size:13px;color:#6b7280;margin-top:12px;\">10 分钟内有效 · 仅可使用一次</div>"
            + "</td></tr>"

            // 说明
            + "<tr><td style=\"padding:0 32px 8px;font-family:-apple-system,"
            + "'PingFang SC',Arial,sans-serif;font-size:14px;line-height:1.7;color:#374151;\">"
            + "你（或使用你账号的人）正在重置 MIM 的登录密码。"
            + "如果不是你本人操作，<strong>忽略本邮件即可</strong>，密码不会改变。"
            + "</td></tr>"

            // 安全提示
            + "<tr><td style=\"padding:8px 32px 28px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background:#fff7ed;border-radius:10px;\">"
            + "<tr><td style=\"padding:14px 16px;font-family:-apple-system,"
            + "'PingFang SC',Arial,sans-serif;font-size:13px;line-height:1.6;color:#9a3412;\">"
            + "MIM 的工作人员<b>永远不会</b>向你索要这个验证码。"
            + "把验证码告诉别人，等于把账号交给对方。"
            + "</td></tr></table>"
            + "</td></tr>"

            // 页脚
            + "<tr><td style=\"padding:16px 32px;background:#fafafa;border-top:1px solid #eef0f3;"
            + "font-family:-apple-system,'PingFang SC',Arial,sans-serif;"
            + "font-size:12px;color:#9ca3af;line-height:1.6;\">"
            + "本邮件由系统自动发出，请勿直接回复。<br>"
            + "MIM · 端到端加密的即时通讯"
            + "</td></tr>"

            + "</table></td></tr></table></body></html>";
    }

    /**
     * HTML 转义。
     *
     * <p>验证码本身只有数字，但模板将来会被复用到带用户输入的内容上，
     * 这里从一开始就转义，避免以后忘记。
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
