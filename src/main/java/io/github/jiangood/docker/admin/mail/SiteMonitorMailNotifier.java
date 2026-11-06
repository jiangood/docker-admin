package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.SiteMonitor;
import io.github.jiangood.openadmin.modules.mail.MailMessage;
import io.github.jiangood.openadmin.modules.mail.service.MailService;
import io.github.jiangood.openadmin.modules.system.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;

/**
 * 网站监控邮件通知：连续失败满阈值发告警邮件，恢复后发恢复通知。
 * <p>
 * 发件账号由框架「系统管理 - 参数设置 - 邮件发送」维护，收件人为监控项创建人的邮箱
 * （与构建通知的口径一致）；发送失败只记录日志，由调用方兜底，不影响检测结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteMonitorMailNotifier {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MailService mailService;
    private final SysUserRepository sysUserRepository;

    /** 失败告警邮件（连续失败满 {@link SiteMonitor#ALERT_THRESHOLD} 次时由服务层调用）。 */
    public void alert(SiteMonitor monitor, String error, Integer statusCode) {
        String to = createUserEmail(monitor);
        if (to == null) {
            log.info("监控项「{}」已开启邮件通知，但创建人未配置邮箱，跳过发送", monitor.getName());
            return;
        }
        if (!mailService.isConfigured()) {
            log.info("未配置邮件发送服务，跳过监控项「{}」的不可达告警", monitor.getName());
            return;
        }
        String subject = "[容器管理平台] 网站不可达 - " + StrUtil.blankToDefault(monitor.getName(), "未知站点");
        try {
            mailService.sendAsync(MailMessage.builder()
                    .to(to).subject(subject)
                    .html(resultHtml(monitor, false, error, statusCode))
                    .build());
        } catch (Exception e) {
            log.warn("告警邮件发送失败，监控项：{}，收件人：{}，原因：{}", monitor.getName(), to, e.getMessage());
        }
    }

    /** 恢复通知邮件（此前发过告警、最近一次检测恢复正常时由服务层调用）。 */
    public void recovered(SiteMonitor monitor) {
        String to = createUserEmail(monitor);
        if (to == null) {
            log.info("监控项「{}」已开启邮件通知，但创建人未配置邮箱，跳过发送", monitor.getName());
            return;
        }
        if (!mailService.isConfigured()) {
            log.info("未配置邮件发送服务，跳过监控项「{}」的恢复通知", monitor.getName());
            return;
        }
        String subject = "[容器管理平台] 网站恢复访问 - " + StrUtil.blankToDefault(monitor.getName(), "未知站点");
        try {
            mailService.sendAsync(MailMessage.builder()
                    .to(to).subject(subject)
                    .html(resultHtml(monitor, true, monitor.getLastError(), monitor.getLastStatusCode()))
                    .build());
        } catch (Exception e) {
            log.warn("恢复邮件发送失败，监控项：{}，收件人：{}，原因：{}", monitor.getName(), to, e.getMessage());
        }
    }

    private String createUserEmail(SiteMonitor monitor) {
        if (monitor == null || StrUtil.isBlank(monitor.getCreateUser())) {
            return null;
        }
        return sysUserRepository.findById(monitor.getCreateUser())
                .map(user -> StrUtil.trimToNull(user.getEmail()))
                .orElse(null);
    }

    private static String resultHtml(SiteMonitor monitor, boolean recovered, String error, Integer statusCode) {
        String statusColor = recovered ? "#16a34a" : "#dc2626";
        String statusText = recovered ? "已恢复正常" : "连续不可达";
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;")
                .append("color:#1f2937;font-size:14px;line-height:1.7\">");
        html.append("<h2 style=\"font-size:18px;margin:0 0 4px;color:").append(statusColor).append("\">")
                .append(escape(statusText)).append("</h2>");
        html.append("<p style=\"margin:0 0 16px;color:#6b7280\">")
                .append(escape(monitor.getName())).append("</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;")
                .append("max-width:680px;border:1px solid #e5e7eb\">");

        appendRow(html, "监控项", monitor.getName());
        appendRow(html, "地址", monitor.getUrl());
        appendRow(html, "方式", StrUtil.blankToDefault(monitor.getMethod(), "GET"));
        appendRow(html, "判定条件", criteriaText(monitor));
        if (!recovered) {
            appendRow(html, "连续失败", String.valueOf(monitor.getFailCount()) + " 次"
                    + "（满 " + SiteMonitor.ALERT_THRESHOLD + " 次告警）");
        }
        appendRow(html, "状态码", statusCode == null ? "-" : String.valueOf(statusCode));
        appendRow(html, "最近结果", recovered ? "正常" : StrUtil.blankToDefault(escape(error), "-"));
        if (monitor.getLastCheckTime() != null) {
            appendRow(html, "检测时间", monitor.getLastCheckTime().format(DATE_TIME_FORMATTER));
        }

        html.append("</table>");
        html.append("<p style=\"margin:16px 0 0;color:#4b5563\">")
                .append(recovered
                        ? "该站点已恢复访问，后续仍按配置的间隔继续检测。"
                        : "平台会按配置的间隔继续检测，恢复访问后会再发一封通知。")
                .append("</p>");
        html.append("</div>");
        return html.toString();
    }

    /** 判定条件文案：把双向关键词翻译成人话，未配置则「按状态码 2xx~3xx」。 */
    private static String criteriaText(SiteMonitor monitor) {
        String keyword = StrUtil.trimToNull(monitor.getKeyword());
        String errorKeyword = StrUtil.trimToNull(monitor.getErrorKeyword());
        if (keyword == null && errorKeyword == null) {
            return "状态码 2xx~3xx";
        }
        StringBuilder text = new StringBuilder("状态码 2xx~3xx");
        if (keyword != null) {
            text.append("，响应包含「").append(escape(keyword)).append("」");
        }
        if (errorKeyword != null) {
            text.append("，响应不包含「").append(escape(errorKeyword)).append("」");
        }
        return text.toString();
    }

    private static void appendRow(StringBuilder html, String label, String value) {
        html.append("<tr>")
                .append("<td style=\"width:110px;border:1px solid #e5e7eb;background:#f9fafb;color:#4b5563;white-space:nowrap\">")
                .append(escape(label)).append("</td>")
                .append("<td style=\"border:1px solid #e5e7eb;word-break:break-all\">")
                .append(value).append("</td>")
                .append("</tr>");
    }

    private static String escape(String value) {
        return StrUtil.isBlank(value) ? "" : HtmlUtils.htmlEscape(value.trim());
    }

}
