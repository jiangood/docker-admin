package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.TunnelClient;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * http-tunnel 客户端管理 API 客户端（客户端仓库：https://github.com/jiangood/http-tunnel）。
 * <p>
 * 平台只通过每个客户端自己的管理 API（启动时的 {@code --api-port}）维护其隧道：
 * <pre>
 *   GET    /api/status
 *   GET    /api/tunnels
 *   PUT    /api/tunnels/{domain}   body: {"local_addr":"host:port"}
 *   DELETE /api/tunnels/{domain}
 * </pre>
 * 认证方式为请求头 {@code Authorization: Bearer <客户端令牌>}；
 * 客户端会把改动转发给服务端（服务端才是唯一事实来源），验证并落盘后再推送给客户端。
 */
@Slf4j
@Service
public class HttpTunnelApiClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public JsonNode status(TunnelClient client) {
        return request(client, "GET", "/api/status", null, false);
    }

    public JsonNode listTunnels(TunnelClient client) {
        return request(client, "GET", "/api/tunnels", null, false);
    }

    public JsonNode putTunnel(TunnelClient client, String domain, String localAddr) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("local_addr", localAddr);
        return request(client, "PUT", "/api/tunnels/" + encode(domain), body, false);
    }

    public void deleteTunnel(TunnelClient client, String domain) {
        request(client, "DELETE", "/api/tunnels/" + encode(domain), null, true);
    }

    // ------------------------------------------------------------------ 内部

    private JsonNode request(TunnelClient client, String method, String path,
                             Map<String, Object> body, boolean ignoreNotFound) {
        Assert.notNull(client, "缺少隧道客户端");
        Assert.hasText(client.apiUrl(), "客户端「" + client.getName() + "」未配置 API 地址");

        String url = base(client) + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (StrUtil.isNotBlank(client.token())) {
            builder.header("Authorization", "Bearer " + client.token());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("请求隧道客户端「" + client.getName() + "」失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new BusinessException("请求隧道客户端「" + client.getName() + "」失败：" + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (ignoreNotFound && status == 404) {
            return null;
        }
        if (status == 401 || status == 403) {
            throw new BusinessException("隧道客户端「" + client.getName() + "」认证失败，请检查令牌");
        }
        if (status == 503) {
            throw new BusinessException("隧道客户端「" + client.getName() + "」未连接到服务端");
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException("隧道客户端「" + client.getName() + "」返回 " + status + "："
                    + errorMessage(response.body()));
        }
        if (StrUtil.isBlank(response.body())) {
            return null;
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BusinessException("解析隧道客户端返回失败：" + e.getMessage(), e);
        }
    }

    private String base(TunnelClient client) {
        String url = StrUtil.trimToEmpty(client.apiUrl());
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        return StrUtil.removeSuffix(url, "/");
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException("构造请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * 客户端错误统一是 {@code {"error":"..."}}，解析出来给用户看，解析不了就退回原文。
     */
    private static String errorMessage(String body) {
        if (StrUtil.isBlank(body)) {
            return "无返回内容";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body).get("error");
            if (node != null && !node.isNull()) {
                return node.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON，退回原文
        }
        return StrUtil.maxLength(body, 200);
    }

    private static String encode(String value) {
        // URLEncoder 会把空格编成 +，路径里需要还原成 %20
        return URLEncoder.encode(StrUtil.nullToEmpty(value), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
