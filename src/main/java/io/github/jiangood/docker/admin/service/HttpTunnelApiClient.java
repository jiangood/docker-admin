package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.TunnelClient;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.impl.NoConnectionReuseStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * http-tunnel 管理 API 客户端（项目：https://github.com/jiangood/http-tunnel）。
 * <p>
 * 自 http-tunnel v1.0.0（2026-10）起，服务端、客户端与中继合并为单个 {@code ht} 进程，
 * 管理 API 与访客流量共用同一个 {@code listen_port}，只接受 Host 等于
 * {@code control_host} 的请求。平台维护的是客户端本地的 {@code [[proxies]]}：
 * <pre>
 *   GET    /api/status
 *   GET    /api/proxies
 *   PUT    /api/proxies/{name}    body: {"domain":"host","local_addr":"host:port"}
 *   DELETE /api/proxies/{name}
 * </pre>
 * 认证方式为请求头 {@code Authorization: Bearer <token>}（即 {@code ht.toml} 的 {@code token}）；
 * 错误体为 {@code {"error":"...","request_id":"..."}}。
 * <p>
 * 管理入口按 Host 路由，而平台通常按 IP 连接客户端，因此需要用 {@link TunnelClient#controlHost()}
 * 单独覆盖 {@code Host} 头。java.net.http 不允许设置 Host，这里使用 Apache HttpClient 5
 * （docker-java 传输层已经引入，版本由 Spring Boot 管理）。
 * <p>
 * 兼容 0.x 的客户端管理 API（{@code /api/tunnels}、独立管理端口）：新路径返回「找不到接口」的
 * 404 时自动回退到旧路径，因此已登记的老客户端仍然可用。
 * <p>
 * 隧道以 {@code name} 为标识（平台用应用 id），{@code domain} 只作为对外域名（按 Host 路由）。
 * 改动由客户端本地校验并原子写回其 {@code ht.toml}；客户端目录不可写时返回 500，
 * 代理列表由 {@code HT_PROXIES} 环境变量提供时返回 409（只读）。
 */
@Slf4j
@Service
public class HttpTunnelApiClient {

    /** http-tunnel v1.0+ 的本地代理接口 */
    private static final String API_PROXIES = "/api/proxies";

    /** http-tunnel 0.x 的隧道接口，保留兼容 */
    private static final String API_TUNNELS = "/api/tunnels";

    /** v1.0+ 对未知 /api 路径的 404 文案，用来区分「接口不存在」与「资源不存在」 */
    private static final String NO_ENDPOINT_HINT = "No such management API endpoint";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CloseableHttpClient httpClient = HttpClients.custom()
            // ht 的管理连接在每个响应后关闭（http1_keep_alive=false），复用只会先拿到 SocketException 再重试
            .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(10))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                    .setResponseTimeout(Timeout.ofSeconds(30))
                    .build())
            .build();

    public JsonNode status(TunnelClient client) {
        return ok(client, call(client, "GET", "/api/status", null));
    }

    public JsonNode listTunnels(TunnelClient client) {
        ApiResponse response = call(client, "GET", API_PROXIES, null);
        if (isMissingEndpoint(response)) {
            response = call(client, "GET", API_TUNNELS, null);
        }
        return ok(client, response);
    }

    public JsonNode putTunnel(TunnelClient client, String name, String domain, String localAddr) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", domain);
        body.put("local_addr", localAddr);
        ApiResponse response = call(client, "PUT", API_PROXIES + "/" + encode(name), body);
        if (isMissingEndpoint(response)) {
            response = call(client, "PUT", API_TUNNELS + "/" + encode(name), body);
        }
        return ok(client, response);
    }

    /**
     * 删除隧道；客户端上本来就没有这条隧道时按成功处理（幂等）。
     */
    public void deleteTunnel(TunnelClient client, String name) {
        ApiResponse response = call(client, "DELETE", API_PROXIES + "/" + encode(name), null);
        if (isMissingEndpoint(response)) {
            response = call(client, "DELETE", API_TUNNELS + "/" + encode(name), null);
        }
        if (response.status() == 404) {
            return;
        }
        ok(client, response);
    }

    // ------------------------------------------------------------------ 内部

    private ApiResponse call(TunnelClient client, String method, String path, Map<String, Object> body) {
        Assert.notNull(client, "缺少隧道客户端");
        Assert.hasText(client.apiUrl(), "客户端「" + client.getName() + "」未配置 API 地址");

        HttpUriRequestBase request = new HttpUriRequestBase(method, URI.create(base(client) + path));
        // v1.0+ 的管理接口只在 Host 等于 control_host 时可达。平台连的可能是 IP，
        // 因此用 controlHost 覆盖 Host；留空时 HttpClient 会按 API 地址自动生成。
        if (StrUtil.isNotBlank(client.controlHost())) {
            request.setHeader("Host", client.controlHost());
        }
        request.setHeader("Accept", "application/json");
        if (StrUtil.isNotBlank(client.token())) {
            request.setHeader("Authorization", "Bearer " + client.token());
        }
        if (body != null) {
            request.setEntity(new StringEntity(writeJson(body), ContentType.APPLICATION_JSON));
        }

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            String text = response.getEntity() == null ? null
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return new ApiResponse(response.getCode(), text);
        } catch (Exception e) {
            String reason = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            throw new BusinessException("请求隧道客户端「" + client.getName() + "」失败：" + reason, e);
        }
    }

    private JsonNode ok(TunnelClient client, ApiResponse response) {
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new BusinessException("隧道客户端「" + client.getName() + "」认证失败，请检查令牌");
        }
        if (status == 409) {
            throw new BusinessException("隧道客户端「" + client.getName() + "」拒绝修改（409）："
                    + errorMessage(response.body())
                    + "。若客户端用 HT_PROXIES 提供代理，请修改环境变量并重启客户端。");
        }
        if (status < 200 || status >= 300) {
            String hint;
            if (isMissingEndpoint(response)) {
                hint = "。客户端可能仍是 http-tunnel 0.x，或 API 地址指向的不是 ht 的管理端口";
            } else if (status == 404) {
                hint = "。请确认 API 地址与控制 Host 是否与客户端 ht.toml 一致";
            } else {
                hint = "";
            }
            throw new BusinessException("隧道客户端「" + client.getName() + "」返回 " + status + "："
                    + errorMessage(response.body()) + hint);
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

    private static boolean isMissingEndpoint(ApiResponse response) {
        if (response.status() != 404) {
            return false;
        }
        // v1.0+ 返回 JSON 并写明接口不存在；0.x 的未知路径是空 body 的 404。
        return StrUtil.isBlank(response.body()) || response.body().contains(NO_ENDPOINT_HINT);
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
     * 客户端错误统一是 {@code {"error":"...","request_id":"..."}}，解析出来给用户看；
     * 带上 request_id 便于到客户端日志里定位，解析不了就退回原文。
     */
    private static String errorMessage(String body) {
        if (StrUtil.isBlank(body)) {
            return "无返回内容";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                String message = error.asText();
                JsonNode requestId = node.get("request_id");
                if (requestId != null && !requestId.isNull()) {
                    message += "（request_id: " + requestId.asText() + "）";
                }
                return message;
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

    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }

    private record ApiResponse(int status, String body) {
    }
}
