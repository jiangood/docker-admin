package io.github.jiangood.docker.admin.mail;

/**
 * 「全局设置 - 邮件发送服务」的保存入参，字段名即前端表单字段名。
 * <p>
 * 授权码留空表示沿用已保存的值，避免每次改其他项都要重填。
 * 代理只传一行地址（{@code http://user:pass@192.168.1.10:8080}），
 * 其中密码写成 {@link MailProxySettings#PASSWORD_MASK} 表示沿用已保存的密码，留空表示不使用代理。
 */
public record EmailSettingsInput(
        String smtpHost,
        Integer smtpPort,
        String username,
        String password,
        String fromName,
        String proxyUrl) {
}
