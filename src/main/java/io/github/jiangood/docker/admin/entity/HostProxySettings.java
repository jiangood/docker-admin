package io.github.jiangood.docker.admin.entity;

import cn.hutool.core.util.StrUtil;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 远程 TCP 主机的连接代理配置，语义与「邮件发送」的代理保持一致：
 * <ul>
 *     <li>{@code HTTP}：向代理发 {@code CONNECT host:port} 建隧道，支持 Basic 认证；</li>
 *     <li>{@code SOCKS5}：RFC 1928 握手，支持用户名密码认证（RFC 1929）。</li>
 * </ul>
 * 代理只负责转发 TCP 字节流：TLS（{@code https://} 前缀）仍是平台与目标 docker 之间端到端，
 * 与直连时的证书行为一致。
 * <p>
 * 为什么需要：Docker 的 TCP 接入本质是 HTTP，平台到目标主机网络不通、但另有一台
 * 两边都能访问的主机时，就可以让那台主机做代理，不必给目标主机开放 2375 或配置 SSH。
 * <p>
 * 界面只填一行地址（{@link #parse(String)}），落库仍拆成 host 表上的五个字段，
 * 回显用 {@link #displayUrl()}（密码只给 {@link #PASSWORD_MASK}），旧数据无需迁移。
 */
public record HostProxySettings(Type type, String host, Integer port, String username, String password) {

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

    public static final HostProxySettings DISABLED = new HostProxySettings(Type.NONE, null, null, null, null);

    /**
     * 从主机连接配置取代理设置（字段本身可能为空，这里统一归一化）。
     */
    public static HostProxySettings of(Host host) {
        if (host == null) {
            return DISABLED;
        }
        return new HostProxySettings(
                Type.parse(host.getProxyType()),
                StrUtil.trimToNull(host.getProxyHost()),
                host.getProxyPort(),
                StrUtil.trimToNull(host.getProxyUsername()),
                host.getProxyPassword());
    }

    /**
     * 解析单行代理地址：
     * <ul>
     *   <li>{@code http://192.168.1.10:8080}</li>
     *   <li>{@code http://user:pass@192.168.1.10:8080}</li>
     *   <li>{@code socks5://user:pass@192.168.1.10:1080}</li>
     * </ul>
     * 留空表示不使用代理；不写 scheme 时按 http 处理；端口必须显式给出。
     * <p>
     * 与邮件代理的差异：SOCKS5 也允许带账号密码（平台自己实现 RFC 1929 握手），
     * 不像 JavaMail 的 SOCKS 实现那样只能匿名。
     */
    public static HostProxySettings parse(String url) {
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

        return new HostProxySettings(type, host, port, username, password);
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

    /**
     * 不打印密码：record 默认 toString 会把所有字段拼出来，日志里一旦出现就等于泄露。
     */
    @Override
    public String toString() {
        return "HostProxySettings[type=" + type + ", host=" + host + ", port=" + port
                + ", username=" + username + "]";
    }
}
