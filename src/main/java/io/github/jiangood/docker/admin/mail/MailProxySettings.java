package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 邮件发送代理配置。
 * <p>
 * 直接复用 Angus Mail（jakarta.mail 的实现）内置的代理能力，不自己写隧道：
 * <ul>
 *   <li>HTTP：{@code mail.smtp.proxy.*}，由 JavaMail 发 {@code CONNECT} 建隧道，支持 Basic 认证；</li>
 *   <li>SOCKS5：{@code mail.smtp.socks.*}，走 JDK 的 SOCKS 实现，不支持账号密码。</li>
 * </ul>
 * 两者都在 SSL（465）与 STARTTLS（587）之前建好 TCP 通道，TLS 仍是端到端到 SMTP 服务器的，
 * 所以证书校验行为与直连一致（JavaMail 默认不校验主机名）。
 */
public record MailProxySettings(Type type, String host, Integer port, String username, String password) {

    public enum Type {
        NONE, HTTP, SOCKS5;

        /** 兼容前端与历史值：空 / 未知一律按不使用代理处理。 */
        public static Type parse(String value) {
            String v = StrUtil.trimToEmpty(value).toUpperCase(Locale.ROOT);
            return switch (v) {
                case "HTTP", "HTTPS" -> HTTP;
                case "SOCKS5", "SOCKS" -> SOCKS5;
                default -> NONE;
            };
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final MailProxySettings DISABLED = new MailProxySettings(Type.NONE, null, null, null, null);

    public static MailProxySettings disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return type != null && type != Type.NONE;
    }

    /** 配置是否完整到可以真正建连（启用代理时地址与端口都必须有值）。 */
    public boolean configured() {
        return enabled() && StrUtil.isNotBlank(host) && port != null && port > 0;
    }

    /** 代理连接串，用于日志与错误提示。 */
    public String address() {
        return StrUtil.trimToEmpty(host) + ":" + (port == null ? "" : port);
    }

    /** JavaMail 需要的代理属性；未启用或配置不完整时返回空。 */
    public Map<String, String> mailProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        if (!configured()) {
            return props;
        }
        if (type == Type.SOCKS5) {
            props.put("mail.smtp.socks.host", host.trim());
            props.put("mail.smtp.socks.port", String.valueOf(port));
            return props;
        }
        props.put("mail.smtp.proxy.host", host.trim());
        props.put("mail.smtp.proxy.port", String.valueOf(port));
        if (StrUtil.isNotBlank(username)) {
            // JavaMail 只在 user 与 password 都非 null 时才发 Proxy-Authorization
            props.put("mail.smtp.proxy.user", username.trim());
            props.put("mail.smtp.proxy.password", StrUtil.nullToEmpty(password));
        }
        return props;
    }
}
