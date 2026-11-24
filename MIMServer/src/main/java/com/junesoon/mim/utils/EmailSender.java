package com.junesoon.mim.utils;

import java.util.Properties;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 邮件发送。只用于找回密码验证码。
 *
 * <p>SMTP 未配置时 {@link #isConfigured()} 为 false，找回密码接口会返回
 * “邮件服务未配置”，不影响其它功能。
 */
@Slf4j
@Component
public class EmailSender {

    @Value("${mim.mail.host:}")
    private String host;

    @Value("${mim.mail.port:465}")
    private int port;

    @Value("${mim.mail.username:}")
    private String username;

    @Value("${mim.mail.password:}")
    private String password;

    @Value("${mim.mail.from:}")
    private String from;

    @Value("${mim.mail.ssl:true}")
    private boolean ssl;

    /**
     * 是否真正可发信。
     *
     * <p>只看 host 是不够的：只填了服务器地址、没填发信地址与 SMTP 密码时，
     * 连接会成功、认证会失败，用户拿到的错误是含糊的「邮件发送失败」。
     * 这里要求三项齐全，缺哪项在启动日志里就能看出来。
     */
    public boolean isConfigured() {
        return StringUtils.hasText(host)
            && StringUtils.hasText(username)
            && StringUtils.hasText(password);
    }

    public void send(String to, String subject, String body) {
        if (!isConfigured()) {
            throw new IllegalStateException("邮件服务未配置");
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", String.valueOf(StringUtils.hasText(username)));
            props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
            if (!ssl) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            } else {
                // 校验 SMTP 服务器证书主机名，避免中间人替换证书
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");

            Authenticator authenticator = null;
            if (StringUtils.hasText(username)) {
                authenticator = new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                };
            }
            Session session = Session.getInstance(props, authenticator);
            MimeMessage message = new MimeMessage(session);
            String sender = StringUtils.hasText(from) ? from : username;
            message.setFrom(new InternetAddress(sender, "MIM"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
        } catch (Exception e) {
            log.warn("发送找回密码邮件失败：{}", e.getMessage());
            throw new IllegalStateException("邮件发送失败", e);
        }
    }
}
