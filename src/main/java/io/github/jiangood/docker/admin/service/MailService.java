package io.github.jiangood.docker.admin.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.BuildResultEvent;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

/**
 * 构建结果邮件通知。
 * <p>
 * SMTP 账号在「设置 - 全局设置」中维护，每次发送时按当前配置创建 {@link JavaMailSenderImpl}，
 * 修改配置后无需重启服务。目前按 QQ 邮箱的 SMTP 参数（smtp.qq.com:465 / SSL）预置默认值。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SystemConfigService systemConfigService;

    /**
     * 构建结束（成功或失败）后异步发送邮件。邮件发送失败只记录日志，不影响构建结果。
     */
    @Async
    @EventListener
    public void onBuildResult(BuildResultEvent event) {
        BuildLog buildLog = event.getBuildLog();
        if (buildLog == null || buildLog.getSuccess() == null) {
            return;
        }
        if (!systemConfigService.isEmailEnabled()) {
            return;
        }
        try {
            sendBuildResult(buildLog);
        } catch (Exception e) {
            log.warn("构建结果邮件发送失败，项目：{}，原因：{}", buildLog.getProjectName(), e.getMessage());
        }
    }

    public void sendBuildResult(BuildLog buildLog) {
        boolean success = Boolean.TRUE.equals(buildLog.getSuccess());
        String statusText = success ? "构建成功" : "构建失败";
        String subject = "[容器管理平台] " + statusText + " - " + StrUtil.blankToDefault(buildLog.getProjectName(), "未知项目")
                + (StrUtil.isNotBlank(buildLog.getTag()) ? " " + buildLog.getTag() : "");
        sendHtml(subject, buildResultHtml(buildLog, success, statusText));
    }

    public void sendTest() {
        String html = "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;"
                + "color:#1f2937;font-size:14px;line-height:1.7\">"
                + "<h2 style=\"font-size:18px;margin:0 0 12px;color:#16a34a\">邮件通知配置可用</h2>"
                + "<p>如果你收到这封邮件，说明 SMTP 配置正确，开启开关后每次构建结束都会收到构建结果。</p>"
                + "</div>";
        sendHtml("[容器管理平台] 邮件通知测试", html);
    }

    /**
     * 通用 HTML 邮件发送入口，供构建结果通知之外的业务复用。
     */
    public void sendHtml(String subject, String html) {
        String username = systemConfigService.getEmailUsername();
        String password = systemConfigService.getEmailPassword();
        String recipients = systemConfigService.getEmailTo();

        Assert.hasText(username, "未配置发件邮箱，请到「设置 - 全局设置」中配置邮件通知");
        Assert.hasText(password, "未配置邮箱授权码，请到「设置 - 全局设置」中配置邮件通知");
        Assert.hasText(recipients, "未配置收件邮箱，请到「设置 - 全局设置」中配置邮件通知");

        List<String> to = SystemConfigService.splitRecipients(recipients);
        if (to.isEmpty()) {
            throw new BusinessException("未配置收件邮箱，请到「设置 - 全局设置」中配置邮件通知");
        }

        try {
            JavaMailSenderImpl sender = createSender(username, password);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            String fromName = systemConfigService.getEmailFromName();
            if (StrUtil.isNotBlank(fromName)) {
                helper.setFrom(username, fromName);
            } else {
                helper.setFrom(username);
            }
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("邮件发送成功，收件人：{}，主题：{}", to, subject);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("邮件发送失败，收件人：{}，原因：{}", to, e.getMessage());
            throw new BusinessException("邮件发送失败：" + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private JavaMailSenderImpl createSender(String username, String password) {
        int port = systemConfigService.getEmailSmtpPort();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(systemConfigService.getEmailSmtpHost());
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        // QQ 邮箱 465 走 SSL；587 走 STARTTLS
        boolean ssl = port == 465;
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.starttls.enable", String.valueOf(!ssl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        return sender;
    }

    private String buildResultHtml(BuildLog buildLog, boolean success, String statusText) {
        String statusColor = success ? "#16a34a" : "#dc2626";
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;")
                .append("color:#1f2937;font-size:14px;line-height:1.7\">");
        html.append("<h2 style=\"font-size:18px;margin:0 0 16px;color:").append(statusColor).append("\">")
                .append(escape(statusText)).append(" - ").append(escape(buildLog.getProjectName()))
                .append("</h2>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;")
                .append("max-width:680px;border:1px solid #e5e7eb\">");

        appendRow(html, "项目", buildLog.getProjectName());
        appendRow(html, "镜像", buildLog.getImageUrl());
        appendRow(html, "版本", buildLog.getTag());
        if (StrUtil.isNotBlank(buildLog.getBranch())) {
            appendRow(html, "分支", buildLog.getBranch());
        }
        appendRow(html, "构建主机", buildLog.getBuildHostName());
        if (buildLog.getTimeSpend() != null && buildLog.getTimeSpend() > 0) {
            appendRow(html, "用时", DateUtil.formatBetween(buildLog.getTimeSpend()));
        }
        if (buildLog.getCompleteTime() != null) {
            appendRow(html, "完成时间", buildLog.getCompleteTime().format(DATE_TIME_FORMATTER));
        }
        if (StrUtil.isNotBlank(buildLog.getCodeMessage())) {
            appendRow(html, "提交信息", StrUtil.maxLength(buildLog.getCodeMessage().trim(), 300));
        }

        html.append("</table>");
        html.append("<p style=\"margin:16px 0 0;color:#6b7280\">")
                .append(success ? "镜像已构建并推送完成。" : "请登录容器管理平台查看完整构建日志。")
                .append("</p>");
        html.append("</div>");
        return html.toString();
    }

    private static void appendRow(StringBuilder html, String label, String value) {
        html.append("<tr>")
                .append("<td style=\"width:110px;border:1px solid #e5e7eb;background:#f9fafb;color:#4b5563;white-space:nowrap\">")
                .append(escape(label)).append("</td>")
                .append("<td style=\"border:1px solid #e5e7eb;word-break:break-all\">")
                .append(escape(value)).append("</td>")
                .append("</tr>");
    }

    private static String escape(String value) {
        return StrUtil.isBlank(value) ? "-" : HtmlUtils.htmlEscape(value.trim());
    }
}
