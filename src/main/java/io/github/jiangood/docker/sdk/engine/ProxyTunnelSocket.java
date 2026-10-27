package io.github.jiangood.docker.sdk.engine;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.HostProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 经代理连接目标端口的 Socket：{@link Socket#connect(SocketAddress)} 实际做的是
 * 「连上代理 → 与代理握手 → 之后读写就是到目标的裸字节流」。
 * <p>
 * 使用方式：把本类当作普通 Socket 交给 HTTP 客户端（hc5），它会在连接建立后自己做 TLS /
 * HTTP。因为隧道在 TLS 之前就建好，https 主机也照常工作，SNI 仍是目标主机名。
 * <p>
 * 两种代理：
 * <ul>
 *     <li>{@code HTTP}：发送 {@code CONNECT host:port HTTP/1.1}，支持 Basic 认证；</li>
 *     <li>{@code SOCKS5}：RFC 1928 握手 + 可选 RFC 1929 用户名密码认证；
 *     域名直接交给代理解析，平台不需要能解析目标主机名。</li>
 * </ul>
 * 握手期间会临时设置 {@code SO_TIMEOUT}（取连接超时），结束后恢复；失败会带上代理地址与
 * 具体原因，方便「测试连接」直接把问题说清楚。
 */
class ProxyTunnelSocket extends Socket {

    /**
     * 调用方没有给超时（0 表示无限等待）时，握手最多等这么久，避免界面一直转圈。
     */
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000;

    /**
     * HTTP 响应头与 SOCKS5 报文的长度上限，防止恶意 / 异常代理把内存撑爆。
     */
    private static final int MAX_LINE_LENGTH = 8 * 1024;
    private static final int MAX_HEADER_LINES = 100;

    private final HostProxySettings proxy;

    ProxyTunnelSocket(HostProxySettings proxy) {
        this.proxy = proxy;
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (!(endpoint instanceof InetSocketAddress target)) {
            throw new IOException("不支持的连接目标: " + endpoint);
        }
        String targetHost = stripBrackets(target.getHostString());
        int targetPort = target.getPort();
        if (StrUtil.isBlank(targetHost) || targetPort <= 0 || targetPort > 65535) {
            throw new IOException("无效的连接目标: " + endpoint);
        }

        // 第一步：连代理本身。这里解析的是代理地址，不是目标地址。
        InetSocketAddress proxyAddress = new InetSocketAddress(stripBrackets(proxy.host()), proxy.port());
        try {
            super.connect(proxyAddress, timeout);
        } catch (IOException e) {
            // 不挂 cause：上层用最内层 message 展示给用户，挂上就只剩 "Connection refused"，
            // 看不出是代理地址连不上
            throw new IOException("连接代理 " + proxy.address() + " 失败：" + e.getMessage());
        }

        int previousTimeout = getSoTimeout();
        try {
            setSoTimeout(timeout > 0 ? timeout : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
            if (proxy.type() == HostProxySettings.Type.SOCKS5) {
                socks5Handshake(targetHost, targetPort);
            } else {
                httpConnect(targetHost, targetPort);
            }
        } catch (IOException e) {
            closeQuietly();
            throw e;
        } finally {
            try {
                setSoTimeout(previousTimeout);
            } catch (SocketException ignored) {
                // 上面失败时已经关掉了 socket，超时设置失败无所谓
            }
        }
    }

    // ------------------------------------------------------------------ HTTP CONNECT

    private void httpConnect(String targetHost, int targetPort) throws IOException {
        String authority = authorityOf(targetHost, targetPort);
        StringBuilder request = new StringBuilder(256);
        request.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(authority).append("\r\n");
        // keep-alive 让代理在 200 之后不要顺手关掉连接
        request.append("Proxy-Connection: keep-alive\r\n");
        if (StrUtil.isNotBlank(proxy.username())) {
            String credentials = proxy.username() + ":" + StrUtil.nullToEmpty(proxy.password());
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            request.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        int status = parseHttpStatus(readHttpLine());
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            if (readHttpLine().isEmpty()) {
                if (status == 200) {
                    return;
                }
                break;
            }
        }

        String hint = switch (status) {
            case 407 -> "代理要求认证，请检查代理用户名和密码";
            case 403 -> "代理拒绝建立隧道（403），请检查代理的访问控制";
            default -> "代理无法建立到目标主机的隧道";
        };
        throw new IOException("HTTP 代理 " + proxy.address() + " 返回 " + status + "（" + hint + "）");
    }

    private static int parseHttpStatus(String statusLine) throws IOException {
        // 形如 HTTP/1.1 200 Connection established
        String[] parts = statusLine.split("\\s+");
        if (parts.length < 2) {
            throw new IOException("HTTP 代理返回了无法识别的响应: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP 代理返回了无法识别的响应: " + statusLine, e);
        }
    }

    /**
     * 逐字节读取一行：不能用 BufferedInputStream，否则会把隧道里后续的 TLS / HTTP 字节吞进缓冲区。
     */
    private String readHttpLine() throws IOException {
        InputStream in = getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("HTTP 代理在握手阶段关闭了连接");
            }
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (buffer.size() >= MAX_LINE_LENGTH) {
                throw new IOException("HTTP 代理响应头过长");
            }
            buffer.write(b);
        }
    }

    // ------------------------------------------------------------------ SOCKS5

    private void socks5Handshake(String targetHost, int targetPort) throws IOException {
        InputStream in = getInputStream();
        OutputStream out = getOutputStream();

        boolean withAuth = StrUtil.isNotBlank(proxy.username());
        // VER=5, NMETHODS, METHODS（匿名 / 用户名密码）
        out.write(withAuth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        out.flush();

        byte[] methodReply = readFully(in, 2);
        if (methodReply[0] != 5) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 返回了无法识别的协议版本: " + (methodReply[0] & 0xFF));
        }
        int method = methodReply[1] & 0xFF;
        if (method == 0xFF) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 不接受平台的认证方式");
        }
        if (method == 2) {
            if (!withAuth) {
                throw new IOException("SOCKS5 代理 " + proxy.address() + " 要求用户名密码认证，请填写代理用户名和密码");
            }
            socks5Authenticate(in, out);
        } else if (method != 0) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 要求不支持的认证方式: " + method);
        }

        // CONNECT：VER=5, CMD=1, RSV=0, ATYP + 地址 + 端口（大端）
        byte[] address = encodeSocksAddress(targetHost);
        ByteArrayOutputStream request = new ByteArrayOutputStream(8 + address.length);
        request.write(5);
        request.write(1);
        request.write(0);
        request.write(address);
        request.write((targetPort >> 8) & 0xFF);
        request.write(targetPort & 0xFF);
        out.write(request.toByteArray());
        out.flush();

        byte[] head = readFully(in, 4);
        if (head[0] != 5) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 返回了无法识别的协议版本: " + (head[0] & 0xFF));
        }
        int reply = head[1] & 0xFF;
        if (reply != 0) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 无法连接 " + targetHost + ":" + targetPort
                    + "（" + socks5ReplyText(reply) + "）");
        }
        // 吃掉 BND.ADDR + BND.PORT，之后就是隧道数据
        switch (head[3] & 0xFF) {
            case 1 -> readFully(in, 4);
            case 4 -> readFully(in, 16);
            case 3 -> {
                int length = readFully(in, 1)[0] & 0xFF;
                readFully(in, length);
            }
            default -> throw new IOException("SOCKS5 代理返回了不支持的地址类型: " + (head[3] & 0xFF));
        }
        readFully(in, 2);
    }

    /**
     * RFC 1929：VER=1, ULEN, UNAME, PLEN, PASSWD；响应 VER=1, STATUS（0 成功）。
     */
    private void socks5Authenticate(InputStream in, OutputStream out) throws IOException {
        byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
        byte[] pass = StrUtil.nullToEmpty(proxy.password()).getBytes(StandardCharsets.UTF_8);
        if (user.length > 255 || pass.length > 255) {
            throw new IOException("SOCKS5 代理用户名或密码超过 255 字节");
        }
        ByteArrayOutputStream request = new ByteArrayOutputStream(3 + user.length + pass.length);
        request.write(1);
        request.write(user.length);
        request.write(user);
        request.write(pass.length);
        request.write(pass);
        out.write(request.toByteArray());
        out.flush();

        byte[] reply = readFully(in, 2);
        if ((reply[1] & 0xFF) != 0) {
            throw new IOException("SOCKS5 代理 " + proxy.address() + " 用户名或密码错误");
        }
    }

    /**
     * 目标地址编码：IP 字面量按 IPv4/IPv6 发送，域名按 ATYP=3 交给代理解析（不在平台本地解析）。
     */
    private static byte[] encodeSocksAddress(String host) throws IOException {
        if (isIpv4Literal(host)) {
            return prepend((byte) 1, InetAddress.getByName(host).getAddress());
        }
        if (host.indexOf(':') >= 0) {
            // 已经是去掉方括号的 IPv6 字面量；getByName 对字面量不会走 DNS
            return prepend((byte) 4, InetAddress.getByName(host).getAddress());
        }
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) {
            throw new IOException("目标主机名超过 SOCKS5 允许的 255 字节: " + host);
        }
        byte[] encoded = new byte[2 + name.length];
        encoded[0] = 3;
        encoded[1] = (byte) name.length;
        System.arraycopy(name, 0, encoded, 2, name.length);
        return encoded;
    }

    private static byte[] prepend(byte first, byte[] rest) {
        byte[] result = new byte[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private static boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String socks5ReplyText(int reply) {
        return switch (reply) {
            case 1 -> "代理内部错误";
            case 2 -> "代理规则不允许连接";
            case 3 -> "网络不可达";
            case 4 -> "主机不可达";
            case 5 -> "连接被拒绝";
            case 6 -> "TTL 过期";
            case 7 -> "代理不支持该命令";
            case 8 -> "代理不支持该地址类型";
            default -> "错误码 " + reply;
        };
    }

    // ------------------------------------------------------------------ 工具方法

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("代理在握手阶段关闭了连接");
            }
            offset += read;
        }
        return buffer;
    }

    private static String authorityOf(String host, int port) {
        String formatted = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return formatted + ":" + port;
    }

    private static String stripBrackets(String host) {
        if (host != null && host.length() > 2 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
        }
    }
}
