package io.github.jiangood.docker.sdk.engine;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.framework.proxy.ProxySettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 代理连通性测试：设置菜单「代理管理」的测试按钮使用。
 * <p>
 * 复用主机接入时的 {@link ProxyTunnelSocket}，因此测试的就是真实连接路径：
 * <ul>
 *     <li>不填目标：只验证平台能否连上代理本身（不校验认证与隧道）；</li>
 *     <li>填了目标：连代理并尝试与目标建立隧道，能同时验证认证与代理的转发能力。</li>
 * </ul>
 */
public final class ProxyTestSupport {

    private ProxyTestSupport() {
    }

    /**
     * 测试结果。{@code ok=false} 时 {@code message} 就是给用户看的原因，不抛异常。
     */
    public record Result(boolean ok, String message, long elapsedMs) {
    }

    public static Result test(ProxySettings proxy, String target, int timeoutMillis) {
        long start = System.currentTimeMillis();
        if (proxy == null || !proxy.enabled()) {
            return new Result(false, "未填写代理类型 / 地址", elapsed(start));
        }
        if (!proxy.configured()) {
            return new Result(false, "代理地址或端口未填写完整", elapsed(start));
        }

        if (StrUtil.isBlank(target)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMillis);
                return new Result(true, "代理 " + proxy.address()
                        + " 可连接（未填写测试目标，未校验认证与隧道）", elapsed(start));
            } catch (Exception e) {
                return new Result(false, "连接代理 " + proxy.address() + " 失败：" + reason(e), elapsed(start));
            }
        }

        Target parsed;
        try {
            parsed = Target.parse(target);
        } catch (Exception e) {
            return new Result(false, reason(e), elapsed(start));
        }

        ProxyTunnelSocket socket = new ProxyTunnelSocket(proxy);
        try {
            // 目标用未解析地址：域名交给代理解析（SOCKS5 / HTTP CONNECT 都是如此），
            // 平台自己解析不了目标主机也能测通。
            socket.connect(InetSocketAddress.createUnresolved(parsed.host(), parsed.port()), timeoutMillis);
            return new Result(true, "代理 " + proxy.address() + " 可连接，并已建立到 "
                    + parsed.host() + ":" + parsed.port() + " 的隧道", elapsed(start));
        } catch (Exception e) {
            return new Result(false, reason(e), elapsed(start));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 测试用的临时连接，关闭失败不影响结果
            }
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 优先取业务异常自带的说明；没有时退到异常类名。
     */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return StrUtil.blankToDefault(message, e.getClass().getSimpleName());
    }

    /**
     * 测试目标，形如 {@code www.baidu.com:443} / {@code [::1]:2375}；
     * 省略端口时按 443 处理（代理最常见的用途就是 HTTPS 隧道）。
     */
    record Target(String host, int port) {

        static Target parse(String text) {
            String target = StrUtil.trim(text);
            if (StrUtil.isBlank(target)) {
                throw new IllegalArgumentException("请填写测试目标，如 www.baidu.com:443");
            }
            int scheme = target.indexOf("://");
            if (scheme >= 0) {
                target = target.substring(scheme + 3);
            }
            int slash = target.indexOf('/');
            if (slash >= 0) {
                target = target.substring(0, slash);
            }

            String host;
            String portText = null;
            if (target.startsWith("[")) {
                int end = target.indexOf(']');
                if (end < 0) {
                    throw new IllegalArgumentException("IPv6 地址格式不正确：" + text);
                }
                host = target.substring(1, end);
                String rest = target.substring(end + 1);
                if (rest.startsWith(":")) {
                    portText = rest.substring(1);
                }
            } else {
                int colon = target.lastIndexOf(':');
                if (colon > 0) {
                    host = target.substring(0, colon);
                    portText = target.substring(colon + 1);
                } else {
                    host = target;
                }
            }

            if (StrUtil.isBlank(host)) {
                throw new IllegalArgumentException("测试目标缺少主机名：" + text);
            }
            int port = 443;
            if (StrUtil.isNotBlank(portText)) {
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("测试目标端口不是数字：" + portText);
                }
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("测试目标端口需在 1-65535 之间：" + port);
            }
            return new Target(host, port);
        }
    }
}
