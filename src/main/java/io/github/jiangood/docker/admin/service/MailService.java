package io.github.jiangood.docker.admin.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.BuildResultEvent;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.dao.ProjectRepository;
import io.github.jiangood.docker.admin.mail.MailProxyProperties;
import io.github.jiangood.docker.admin.proxy.ProxySettings;
import io.github.jiangood.openadmin.modules.logviewer.config.FileLogConfig;
import io.github.jiangood.openadmin.modules.system.entity.SysUser;
import io.github.jiangood.openadmin.modules.system.repository.SysUserRepository;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/**
 * 全局邮件发送服务（目前为 QQ 邮箱 SMTP）。
 * <p>
 * SMTP 账号在「设置 - 全局设置」中维护，每次发送时按当前配置创建 {@link JavaMailSenderImpl}，
 * 修改配置后无需重启服务。邮件通知由项目详情页的开关控制，收件人为项目创建人的邮箱，
 * 邮件会附带该次构建的日志文件。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SystemConfigService systemConfigService;
    private final ProjectRepository projectRepository;
    private final SysUserRepository sysUserRepository;
    private final FileLogConfig fileLogConfig;

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

        Project project = projectRepository.findById(buildLog.getProjectId()).orElse(null);
        if (project == null || !Boolean.TRUE.equals(project.getNotifyEmail())) {
            return;
        }

        String to = createUserEmail(project);
        if (StrUtil.isBlank(to)) {
            log.info("项目 {} 已开启邮件通知，但创建人未配置邮箱，跳过发送", project.getName());
            return;
        }

        try {
            sendBuildResult(buildLog, to);
        } catch (Exception e) {
            log.warn("邮件发送失败，项目：{}，收件人：{}，原因：{}", project.getName(), to, e.getMessage());
        }
    }

    /** 项目创建人的邮箱；创建人不存在或未配置邮箱时返回 null。 */
    private String createUserEmail(Project project) {
        if (StrUtil.isBlank(project.getCreateUser())) {
            return null;
        }
        return sysUserRepository.findById(project.getCreateUser())
                .map(SysUser::getEmail)
                .map(StrUtil::trimToNull)
                .orElse(null);
    }

    public void sendBuildResult(BuildLog buildLog, String to) {
        boolean success = Boolean.TRUE.equals(buildLog.getSuccess());
        String statusText = success ? "构建成功" : "构建失败";
        String version = imageVersion(buildLog);
        String subject = "[容器管理平台] " + statusText + " - " + StrUtil.blankToDefault(buildLog.getProjectName(), "未知项目")
                + (StrUtil.isNotBlank(version) ? " " + version : "");

        // 附带本次构建的日志文件（${logging.file.path}/{logId}.log）
        File logFile = fileLogConfig.buildLogFile(buildLog.getId());
        String attachmentName = null;
        if (logFile.isFile()) {
            attachmentName = buildLogAttachmentName(buildLog);
        } else {
            log.warn("构建日志文件不存在，邮件不附带日志：{}", logFile.getAbsolutePath());
            logFile = null;
        }

        sendHtml(to, subject, buildResultHtml(buildLog, success, statusText, attachmentName), logFile, attachmentName);
    }

    /** 发送测试邮件到发件邮箱自己，用于验证 SMTP 配置。 */
    public void sendTest() {
        String html = "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;"
                + "color:#1f2937;font-size:14px;line-height:1.7\">"
                + "<h2 style=\"font-size:18px;margin:0 0 12px;color:#16a34a\">邮件发送服务可用</h2>"
                + "<p>如果你收到这封邮件，说明 SMTP 配置正确。在项目详情页打开「邮件通知」后，"
                + "每次构建结束都会给项目创建人发送结果邮件。</p>"
                + "</div>";
        sendHtml(systemConfigService.getEmailUsername(), "[容器管理平台] 邮件通知测试", html);
    }

    /**
     * 通用 HTML 邮件发送入口，供邮件通知之外的业务复用。
     */
    public void sendHtml(String to, String subject, String html) {
        sendHtml(to, subject, html, null, null);
    }

    private void sendHtml(String to, String subject, String html, File attachment, String attachmentName) {
        String username = systemConfigService.getEmailUsername();
        String password = systemConfigService.getEmailPassword();
        ProxySettings proxy = systemConfigService.getEmailProxy();

        Assert.hasText(username, "未配置发件邮箱，请到「设置 - 全局设置」中配置邮件发送服务");
        Assert.hasText(password, "未配置邮箱授权码，请到「设置 - 全局设置」中配置邮件发送服务");
        Assert.hasText(to, "收件邮箱不能为空");
        if (proxy.enabled()) {
            Assert.isTrue(proxy.configured(),
                    "已启用邮件代理但代理地址或端口为空，请到「设置 - 全局设置」中补全");
        }

        try {
            JavaMailSenderImpl sender = createSender(username, password, proxy);
            MimeMessage message = sender.createMimeMessage();
            boolean multipart = attachment != null;
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
            String fromName = systemConfigService.getEmailFromName();
            if (StrUtil.isNotBlank(fromName)) {
                helper.setFrom(username, fromName);
            } else {
                helper.setFrom(username);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (multipart) {
                helper.addAttachment(attachmentName, attachment);
            }
            sender.send(message);
            log.info("邮件发送成功，收件人：{}，主题：{}，附件：{}，代理：{}", to, subject,
                    multipart ? attachmentName : "无", proxy.enabled() ? proxy.address() : "无");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("邮件发送失败，收件人：{}，原因：{}", to, e.getMessage());
            throw new BusinessException(failureMessage(e, proxy));
        }
    }

    /**
     * 代理链路失败时补一句人话：JavaMail 的原生报错是
     * {@code connection through proxy host:port to ... failed: HTTP/1.1 407 ...}，
     * 直接抛给用户看不出是代理的问题。
     */
    private static String failureMessage(Exception e, ProxySettings proxy) {
        String message = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        if (proxy.enabled() && mentionsProxy(e)) {
            return "邮件代理 " + proxy.address() + " 连接失败：" + message;
        }
        return "邮件发送失败：" + message;
    }

    private static boolean mentionsProxy(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("proxy")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 附件文件名：构建日志-项目名-版本.log（去掉文件系统与邮件头不允许的字符）。 */
    private static String buildLogAttachmentName(BuildLog buildLog) {
        String name = StrUtil.blankToDefault(buildLog.getProjectName(), "project");
        String version = imageVersion(buildLog);
        if (StrUtil.isNotBlank(version)) {
            name += "-" + version;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return "构建日志-" + StrUtil.maxLength(name, 80) + ".log";
    }

    /**
     * 实际推送的镜像版本：开启「仅构建 latest」时 {@code tag} 中的版本号不会成为镜像标签，
     * 真实标签是 latest。
     */
    private static String imageVersion(BuildLog buildLog) {
        return Boolean.TRUE.equals(buildLog.getLatestOnly()) ? "latest" : buildLog.getTag();
    }

    /** 构建来源文案：分支 main / tag v1.0.1；老记录没有 refType/refName 时退回 branch/tag。 */
    private static String sourceText(BuildLog buildLog) {
        boolean branch = BuildRequest.REF_TYPE_BRANCH.equals(buildLog.getRefType())
                || (buildLog.getRefType() == null && StrUtil.isNotBlank(buildLog.getBranch()));
        String name = branch
                ? StrUtil.blankToDefault(buildLog.getRefName(), buildLog.getBranch())
                : StrUtil.blankToDefault(buildLog.getRefName(), buildLog.getTag());
        String label = branch ? "分支" : "tag";
        return StrUtil.isBlank(name) ? label : label + " " + name;
    }

    private JavaMailSenderImpl createSender(String username, String password, ProxySettings proxy) {
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
        // 代理交给 JavaMail 原生实现：HTTP 走 CONNECT 隧道（可带 Basic 认证），SOCKS5 走 JDK 实现。
        // 隧道在 TLS 之前建好，证书仍是 SMTP 服务器的，不影响 465/587 两条链路。
        MailProxyProperties.of(proxy).forEach(props::put);
        return sender;
    }

    private String buildResultHtml(BuildLog buildLog, boolean success, String statusText, String attachmentName) {
        String statusColor = success ? "#16a34a" : "#dc2626";
        String version = imageVersion(buildLog);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;")
                .append("color:#1f2937;font-size:14px;line-height:1.7\">");
        // 标题只放结果状态，项目与版本作为副标题，避免与邮件主题重复一遍
        html.append("<h2 style=\"font-size:18px;margin:0 0 4px;color:").append(statusColor).append("\">")
                .append(escape(statusText)).append("</h2>");
        html.append("<p style=\"margin:0 0 16px;color:#6b7280\">")
                .append(escape(buildLog.getProjectName()))
                .append(StrUtil.isBlank(version) ? "" : " · " + escape(version))
                .append("</p>");
        html.append("<table cellpadding=\"8\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;")
                .append("max-width:680px;border:1px solid #e5e7eb\">");

        appendRow(html, "项目", buildLog.getProjectName());
        appendRow(html, "镜像", buildLog.getImageUrl());
        appendRow(html, "版本", version);
        appendRow(html, "来源", sourceText(buildLog));
        String commit = commitText(buildLog);
        if (StrUtil.isNotBlank(commit)) {
            appendRow(html, "提交", commit);
        }
        appendRow(html, "构建主机", buildLog.getBuildHostName());
        if (buildLog.getTimeSpend() != null && buildLog.getTimeSpend() > 0) {
            appendRow(html, "用时", DateUtil.formatBetween(buildLog.getTimeSpend()));
        }
        if (buildLog.getCompleteTime() != null) {
            appendRow(html, "完成时间", buildLog.getCompleteTime().format(DATE_TIME_FORMATTER));
        }

        html.append("</table>");
        html.append("<p style=\"margin:16px 0 0;color:#4b5563\">")
                .append(success
                        ? "镜像已推送至 " + escape(pushedImage(buildLog)) + "。"
                        : "构建未完成，请按日志中的错误处理后重新构建。")
                .append("</p>");
        if (StrUtil.isNotBlank(attachmentName)) {
            html.append("<p style=\"margin:8px 0 0;color:#6b7280\">完整构建日志见附件：")
                    .append(escape(attachmentName))
                    .append("</p>");
        }
        html.append("</div>");
        return html.toString();
    }

    /** 提交信息行：短 id + 提交说明（主干信息，超长截断）。 */
    private static String commitText(BuildLog buildLog) {
        String commitId = StrUtil.trimToNull(buildLog.getCommitId());
        String message = StrUtil.trimToNull(buildLog.getCodeMessage());
        if (message != null) {
            message = StrUtil.maxLength(message, 120);
        }
        if (commitId == null) {
            return message;
        }
        return message == null ? commitId : commitId + " " + message;
    }

    /** 本次实际推送的镜像全名：镜像地址 + 版本。 */
    private static String pushedImage(BuildLog buildLog) {
        String image = StrUtil.trimToNull(buildLog.getImageUrl());
        String version = imageVersion(buildLog);
        if (image == null) {
            return StrUtil.blankToDefault(version, "-");
        }
        return StrUtil.isBlank(version) ? image : image + ":" + version;
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
