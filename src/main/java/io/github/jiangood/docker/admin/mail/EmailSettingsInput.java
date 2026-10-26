package io.github.jiangood.docker.admin.mail;

/**
 * 「全局设置 - 邮件发送服务」的保存入参，字段名即前端表单字段名。
 * <p>
 * 授权码与代理密码留空表示沿用已保存的值，避免每次改其他项都要重填。
 */
public record EmailSettingsInput(
        String smtpHost,
        Integer smtpPort,
        String username,
        String password,
        String fromName,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword) {
}
