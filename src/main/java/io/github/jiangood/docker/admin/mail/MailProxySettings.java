package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.util.StrUtil;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 * <p>
 * 界面只填一行地址，落库仍拆成 type/host/port/username/password 五个键
 * （见 {@link #parse(String)} 与 {@link #displayUrl()}），旧数据无需迁移。
 */
public record MailProxySettings(Type type, String host, Integer port, String username, String password) {

    /** 回显密码用的占位符：保存时原样出现表示沿用已保存的密码。 */
    public static final String PASSWORD_MASK = "***";

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

    /**
     * 解析单行代理地址：
     * <ul>
     *   <li>{@code http://192.168.1.10:8080}</li>
     *   <li>{@code http://user:pass@192.168.1.10:8080}</li>
     *   <li>{@code socks5://192.168.1.10:1080}</li>
     * </ul>
     * 留空表示不使用代理；不写 scheme 时按 http 处理；端口必须显式给出。
     */
    public static MailProxySettings parse(String url) {
        String raw = StrUtil.trimToEmpty(url);
        if (raw.isEmpty()) {
            return DISABLED;
        }

        String value = raw.contains("://") ? raw : "http://" + raw;
        int schemeEnd = value.indexOf("://");
        String scheme = value.substring(0, schemeEnd).trim().toLowerCase(Locale.ROOT);
        Type type = switch (scheme) {
            case "http", "https" -> Type.HTTP;
            case "socks", "socks5" -> Type.SOCKS5;
            default -> throw new IllegalArgumentException("代理地址只支持 http:// 或 socks5:// 前缀：" + raw);
        };

        String rest = value.substring(schemeEnd + 3).trim();
        // 误填的路径不影响解析
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash).trim();
        }
        Assert.isTrue(!rest.isEmpty(), "代理地址缺少主机，如 http://192.168.1.10:8080");

        // 以最后一个 @ 切分：密码里的 @ 不会把主机截断
        String userInfo = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1).trim();
        }

        String host;
        String portText = null;
        if (rest.startsWith("[")) {
            // IPv6 字面量：[::1]:8080
            int close = rest.indexOf(']');
            Assert.isTrue(close > 1, "代理地址格式不正确：" + raw);
            host = rest.substring(1, close).trim();
            String after = rest.substring(close + 1).trim();
            if (!after.isEmpty()) {
                Assert.isTrue(after.startsWith(":"), "代理地址格式不正确：" + raw);
                portText = after.substring(1).trim();
            }
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) {
                portText = rest.substring(colon + 1).trim();
                host = rest.substring(0, colon).trim();
            } else {
                host = rest;
            }
        }
        Assert.hasText(host, "代理地址缺少主机，如 http://192.168.1.10:8080");
        Assert.hasText(portText, "代理地址需包含端口，如 http://192.168.1.10:8080");
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("代理端口不正确：" + portText);
        }
        Assert.isTrue(port > 0 && port <= 65535, "代理端口不正确：" + portText);

        String username = null;
        String password = null;
        if (StrUtil.isNotBlank(userInfo)) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = percentDecode(userInfo.substring(0, colon));
                password = percentDecode(userInfo.substring(colon + 1));
            } else {
                username = percentDecode(userInfo);
            }
            username = StrUtil.trimToNull(username);
            password = StrUtil.trimToNull(password);
        }
        Assert.isTrue(type != Type.SOCKS5 || username == null,
                "SOCKS5 代理不支持账号密码，需要认证请改用 http://用户名:密码@主机:端口");

        return new MailProxySettings(type, host, port, username, password);
    }

    public boolean enabled() {
        return type != null && type != Type.NONE;
    }

    /** 配置是否完整到可以真正建连（启用代理时地址与端口都必须有值）。 */
    public boolean configured() {
        return enabled() && StrUtil.isNotBlank(host) && port != null && port > 0;
    }

    /** 代理连接串，用于日志与错误提示（不含凭据）。 */
    public String address() {
        return StrUtil.trimToEmpty(host) + ":" + (port == null ? "" : port);
    }

    /** 回显用的单行地址；已保存的密码只给 {@link #PASSWORD_MASK}，不把明文送到前端。 */
    public String displayUrl() {
        if (!enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(type == Type.SOCKS5 ? "socks5" : "http").append("://");
        if (StrUtil.isNotBlank(username)) {
            sb.append(username);
            if (password != null) {
                sb.append(':').append(PASSWORD_MASK);
            }
            sb.append('@');
        }
        String h = StrUtil.trimToEmpty(host);
        if (h.contains(":") && !h.startsWith("[")) {
            sb.append('[').append(h).append(']');
        } else {
            sb.append(h);
        }
        if (port != null) {
            sb.append(':').append(port);
        }
        return sb.toString();
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

    /** 只处理 %XX，不把 '+' 当空格：代理密码里 '+' 很常见。 */
    private static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                try {
                    out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 不是合法的 %XX，按普通字符处理
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
