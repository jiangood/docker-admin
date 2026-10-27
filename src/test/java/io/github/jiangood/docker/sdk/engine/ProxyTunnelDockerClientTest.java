package io.github.jiangood.docker.sdk.engine;

import com.github.dockerjava.transport.DockerHttpClient;
import io.github.jiangood.docker.admin.proxy.ProxySettings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地假代理 + 假 Docker HTTP 服务验证「远程 TCP 经代理接入」真的能走通：
 * <ul>
 *     <li>HTTP CONNECT：带 Basic 认证，且请求确实被转发到目标；</li>
 *     <li>SOCKS5：带用户名密码认证，域名/IP 由代理侧解析。</li>
 * </ul>
 * 不依赖外网、不依赖真实 docker。
 */
class ProxyTunnelDockerClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void httpProxyWithBasicAuthTunnelsDockerApi() throws Exception {
        try (FakeDocker docker = new FakeDocker();
             HttpConnectProxy proxy = new HttpConnectProxy("user", "secret")) {
            ProxySettings settings = new ProxySettings(
                    ProxySettings.Type.HTTP, "127.0.0.1", proxy.port(), "user", "secret");

            String body = executeVersion(docker, settings);

            assertTrue(body.contains("\"ApiVersion\""), body);
            proxy.await();
            assertEquals("127.0.0.1:" + docker.port(), proxy.connectTarget.get());
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, proxy.authorization.get());
        }
    }

    @Test
    void socks5ProxyAuthenticatesAndTunnelsDockerApi() throws Exception {
        try (FakeDocker docker = new FakeDocker();
             Socks5Proxy proxy = new Socks5Proxy("user", "secret")) {
            ProxySettings settings = new ProxySettings(
                    ProxySettings.Type.SOCKS5, "127.0.0.1", proxy.port(), "user", "secret");

            String body = executeVersion(docker, settings);

            assertTrue(body.contains("\"ApiVersion\""), body);
            proxy.await();
            assertEquals("user", proxy.username.get());
            assertEquals("secret", proxy.passwordRef.get());
            assertEquals("127.0.0.1:" + docker.port(), proxy.connectTarget.get());
        }
    }

    private static String executeVersion(FakeDocker docker, ProxySettings settings) throws Exception {
        URI dockerHost = URI.create("tcp://127.0.0.1:" + docker.port());
        try (ProxyAwareApacheDockerHttpClient client = new ProxyAwareApacheDockerHttpClient(
                dockerHost, null, 4, TIMEOUT, TIMEOUT, settings)) {
            DockerHttpClient.Response response = client.execute(
                    DockerHttpClient.Request.builder().method("GET").path("/version").build());
            try (InputStream body = response.getBody()) {
                assertEquals(200, response.getStatusCode());
                return new String(body.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                response.close();
            }
        }
    }

    // ------------------------------------------------------------------ 假 Docker 服务

    /**
     * 收到任意请求都回一个固定 JSON；回完即关连接，模拟 {@code /version}。
     */
    static final class FakeDocker implements Closeable {

        static final String BODY = "{\"ApiVersion\":\"1.47\",\"Version\":\"27.0.0\"}";

        private final ServerSocket server;
        private final Thread thread;
        private final CompletableFuture<Void> handled = new CompletableFuture<>();

        FakeDocker() throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-docker");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                readLine(socket.getInputStream()); // request line
                String line;
                while (!(line = readLine(socket.getInputStream())).isEmpty()) {
                    // 吃掉请求头
                }
                byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
                handled.complete(null);
            } catch (Throwable e) {
                handled.completeExceptionally(e);
            }
        }

        void await() throws Exception {
            handled.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    // ------------------------------------------------------------------ 假 HTTP 代理

    /**
     * 只处理一次连接的 HTTP CONNECT 代理：校验认证头，然后把字节流双向转发。
     */
    static final class HttpConnectProxy implements Closeable {

        private final ServerSocket server;
        private final String user;
        private final String password;
        private final Thread thread;
        private final CompletableFuture<Void> handled = new CompletableFuture<>();
        final AtomicReference<String> connectTarget = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();

        HttpConnectProxy(String user, String password) throws IOException {
            this.user = user;
            this.password = password;
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-http-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                String requestLine = readLine(in);
                String[] parts = requestLine.split(" ");
                assertTrue(parts[0].equals("CONNECT"), "代理收到非 CONNECT 请求：" + requestLine);
                connectTarget.set(parts[1]);

                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Proxy-Authorization")) {
                        authorization.set(line.substring(colon + 1).trim());
                    }
                }

                String expected = "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(authorization.get())) {
                    out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    handled.complete(null);
                    return;
                }

                String[] target = parts[1].split(":");
                try (Socket upstream = new Socket(target[0], Integer.parseInt(target[1]))) {
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    pump(client, upstream);
                }
                handled.complete(null);
            } catch (Throwable e) {
                handled.completeExceptionally(e);
            }
        }

        void await() throws Exception {
            handled.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    // ------------------------------------------------------------------ 假 SOCKS5 代理

    /**
     * 只处理一次连接的 SOCKS5 代理，要求 RFC 1929 用户名密码认证，然后双向转发。
     */
    static final class Socks5Proxy implements Closeable {

        private final ServerSocket server;
        private final String user;
        private final String password;
        private final Thread thread;
        private final CompletableFuture<Void> handled = new CompletableFuture<>();
        final AtomicReference<String> username = new AtomicReference<>();
        final AtomicReference<String> passwordRef = new AtomicReference<>();
        final AtomicReference<String> connectTarget = new AtomicReference<>();

        Socks5Proxy(String user, String password) throws IOException {
            this.user = user;
            this.password = password;
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-socks5-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                assertEquals(5, in.read());
                int methodCount = in.read();
                boolean offersUserPassword = false;
                for (int i = 0; i < methodCount; i++) {
                    offersUserPassword |= in.read() == 2;
                }
                assertTrue(offersUserPassword, "客户端未提供用户名密码认证方式");
                out.write(new byte[]{5, 2});
                out.flush();

                assertEquals(1, in.read());
                byte[] name = readFully(in, in.read());
                byte[] pass = readFully(in, in.read());
                username.set(new String(name, StandardCharsets.UTF_8));
                passwordRef.set(new String(pass, StandardCharsets.UTF_8));
                boolean authenticated = user.equals(username.get()) && password.equals(passwordRef.get());
                out.write(new byte[]{1, (byte) (authenticated ? 0 : 1)});
                out.flush();
                if (!authenticated) {
                    handled.complete(null);
                    return;
                }

                assertEquals(5, in.read());
                assertEquals(1, in.read()); // CONNECT
                in.read(); // RSV
                int addressType = in.read();
                String host;
                if (addressType == 1) {
                    host = InetAddress.getByAddress(readFully(in, 4)).getHostAddress();
                } else if (addressType == 3) {
                    host = new String(readFully(in, in.read()), StandardCharsets.UTF_8);
                } else {
                    throw new AssertionError("不支持的地址类型：" + addressType);
                }
                int targetPort = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                connectTarget.set(host + ":" + targetPort);

                try (Socket upstream = new Socket(host, targetPort)) {
                    int boundPort = upstream.getLocalPort();
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1,
                            (byte) (boundPort >> 8), (byte) boundPort});
                    out.flush();
                    pump(client, upstream);
                }
                handled.complete(null);
            } catch (Throwable e) {
                handled.completeExceptionally(e);
            }
        }

        void await() throws Exception {
            handled.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 逐字节读一行（不预读），避免把隧道里的业务字节吞进代理缓冲区。
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("连接提前关闭");
            }
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            buffer.write(b);
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("连接提前关闭");
            }
            offset += read;
        }
        return buffer;
    }

    /**
     * 单向转发 upstream → client，结束后关掉两端；反方向由另一个线程负责。
     */
    private static void pump(Socket client, Socket upstream) {
        Thread other = new Thread(() -> copy(upstream, client), "pump-upstream");
        other.setDaemon(true);
        other.start();
        copy(client, upstream);
        try {
            other.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(client);
        closeQuietly(upstream);
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // 一端关闭导致的读写失败是正常收尾
        } finally {
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
