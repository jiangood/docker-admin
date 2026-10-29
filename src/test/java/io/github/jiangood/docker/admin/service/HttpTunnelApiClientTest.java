package io.github.jiangood.docker.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jiangood.docker.admin.entity.TunnelClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * http-tunnel v1.0+ 管理 API 的请求形态：
 * 路径改为 /api/proxies、Host 取 control_host、令牌走 Bearer；0.x 的 /api/tunnels 自动回退。
 */
class HttpTunnelApiClientTest {

    private HttpServer server;

    private HttpTunnelApiClient api;

    private final List<Recorded> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        api = new HttpTunnelApiClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        api.close();
        server.stop(0);
    }

    @Test
    void putUsesProxiesPathAndOverridesHost() {
        TunnelClient client = client("edge.lan");

        JsonNode node = api.putTunnel(client, "app 1", "app.example.com", "10.0.0.5:8080");

        assertEquals("app 1", node.path("name").asText());
        assertEquals(1, requests.size());
        Recorded request = requests.get(0);
        assertEquals("PUT", request.method);
        assertEquals("/api/proxies/app%201", request.path);
        assertEquals("edge.lan", request.host);
        assertEquals("Bearer secret", request.authorization);
        assertTrue(request.body.contains("\"domain\":\"app.example.com\""));
        assertTrue(request.body.contains("\"local_addr\":\"10.0.0.5:8080\""));
    }

    @Test
    void listFallsBackToTheLegacyTunnelsPath() {
        TunnelClient client = client(null);

        JsonNode list = api.listTunnels(client);

        assertEquals("legacy", list.get(0).path("name").asText());
        assertEquals(List.of("/api/proxies", "/api/tunnels"),
                requests.stream().map(r -> r.path).toList());
    }

    @Test
    void statusReadsTheNewProxyCount() {
        TunnelClient client = client(null);

        JsonNode status = api.status(client);

        assertEquals(2, status.path("proxies").asInt());
    }

    private TunnelClient client(String controlHost) {
        TunnelClient client = new TunnelClient();
        client.setName("home");
        client.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client.setControlHost(controlHost);
        client.setToken("secret");
        return client;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(method, path,
                exchange.getRequestHeaders().getFirst("Host"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));

        if ("/api/status".equals(path)) {
            reply(exchange, 200, "{\"proxies\":2,\"connected\":true}");
        } else if ("/api/proxies".equals(path)) {
            // 模拟 0.x 客户端：没有新路径，返回空 body 的 404
            reply(exchange, 404, "");
        } else if ("/api/tunnels".equals(path)) {
            reply(exchange, 200, "[{\"name\":\"legacy\",\"domain\":\"old.example.com\",\"local_addr\":\"127.0.0.1:80\"}]");
        } else if (path.startsWith("/api/proxies/")) {
            reply(exchange, 201, "{\"name\":\"app 1\"}");
        } else {
            reply(exchange, 404, "");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private record Recorded(String method, String path, String host, String authorization, String body) {
    }
}
