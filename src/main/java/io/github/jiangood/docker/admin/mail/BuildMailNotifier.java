package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.BuildResultEvent;
import io.github.jiangood.docker.admin.dao.ProjectRepository;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.ProjectService;
import io.github.jiangood.openadmin.modules.logviewer.config.FileLogConfig;
import io.github.jiangood.openadmin.modules.mail.MailAttachment;
import io.github.jiangood.openadmin.modules.mail.MailMessage;
import io.github.jiangood.openadmin.modules.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.File;
import java.time.format.DateTimeFormatter;

/**
 * 构建结果邮件通知。
 * <p>
 * 发件账号由框架「系统管理 - 系统参数 - 邮件发送」维护（{@code email.*} 配置项），
 * 是否发送由「项目详情页」的「邮件通知」开关控制，收件人为项目创建人的邮箱。
 * 邮件会附带该次构建的日志文件；发送失败只记录日志，不影响构建结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildMailNotifier {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MailService mailService;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final FileLogConfig fileLogConfig;

    /** 构建结束（成功或失败）后异步发送邮件。 */
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

        String to = projectService.findCreateUserEmail(project);
        if (StrUtil.isBlank(to)) {
            log.info("项目 {} 已开启邮件通知，但创建人未配置邮箱，跳过发送", project.getName());
            return;
        }

        if (!mailService.isConfigured()) {
            log.info("未配置邮件发送服务，跳过项目 {} 的构建通知", project.getName());
            return;
        }

        try {
            mailService.sendAsync(buildResultMessage(buildLog, to));
        } catch (Exception e) {
            log.warn("邮件发送失败，项目：{}，收件人：{}，原因：{}", project.getName(), to, e.getMessage());
        }
    }

    private MailMessage buildResultMessage(BuildLog buildLog, String to) {
        boolean success = Boolean.TRUE.equals(buildLog.getSuccess());
        String statusText = success ? "构建成功" : "构建失败";
        String version = imageVersion(buildLog);
        String subject = "[容器管理平台] " + statusText + " - "
                + StrUtil.blankToDefault(buildLog.getProjectName(), "未知项目")
                + (StrUtil.isNotBlank(version) ? " " + version : "");

        // 附带本次构建的日志文件（${logging.file.path}/{logId}.log）
        File logFile = fileLogConfig.buildLogFile(buildLog.getId());
        String attachmentName = null;
        MailMessage.MailMessageBuilder builder = MailMessage.builder().to(to).subject(subject);
        if (logFile.isFile()) {
            attachmentName = buildLogAttachmentName(buildLog);
            builder.attachment(MailAttachment.of(attachmentName, logFile));
        } else {
            log.warn("构建日志文件不存在，邮件不附带日志：{}", logFile.getAbsolutePath());
        }

        return builder.html(buildResultHtml(buildLog, success, statusText, attachmentName)).build();
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

    private static String buildResultHtml(BuildLog buildLog, boolean success, String statusText, String attachmentName) {
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
