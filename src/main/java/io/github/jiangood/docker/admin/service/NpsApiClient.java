package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * nps WebAPI 客户端。
 * <p>
 * 鉴权方式：先 {@code GET /auth/gettime/} 取服务端时间戳（免鉴权），
 * 再用 {@code auth_key = md5(conf 中的 auth_key + timestamp)} 作为表单参数，
 * 与 {@code timestamp} 一起提交；有效期 20 秒，每次请求重新生成。
 */
@Slf4j
@Service
public class NpsApiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * nps 的 WebAPI 基地址。
     */
    public String baseUrl(TunnelSetting s) {
        String addr = StrUtil.trim(s.getNpsAddr());
        if (StrUtil.isBlank(addr)) {
            throw new BusinessException("未配置 nps 连接地址");
        }
        if (addr.startsWith("http://") || addr.startsWith("https://")) {
            return StrUtil.removeSuffix(addr, "/");
        }
        return "http://" + addr + ":" + s.webPort();
    }

    /**
     * 取服务端时间戳（免鉴权）。连通性探测也用它。
     */
    public long serverTime(TunnelSetting s) {
        String body = get(baseUrl(s) + "/auth/gettime/");
        JsonNode node = read(body);
        JsonNode time = node.get("time");
        if (time == null) {
            throw new BusinessException("nps 未返回时间戳：" + StrUtil.maxLength(body, 200));
        }
        return time.asLong();
    }

    /**
     * 探测 WebAPI 是否可用。
     */
    public boolean ping(TunnelSetting s) {
        try {
            serverTime(s);
            return true;
        } catch (Exception e) {
            log.debug("nps WebAPI 探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 签名：md5(auth_key + timestamp)。
     */
    public String sign(String authKey, long timestamp) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((StrUtil.nullToEmpty(authKey) + timestamp).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("计算 nps 签名失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 客户端

    public JsonNode listClients(TunnelSetting s) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("search", "");
        form.put("order", "asc");
        form.put("offset", "0");
        form.put("limit", "100");
        return post(s, "/client/list/", form);
    }

    /**
     * 新建客户端，返回客户端 id。
     *
     * @param vkey 客户端验证密钥（节点用它与 nps 建立连接）
     */
    public int addClient(TunnelSetting s, String vkey, String remark) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("vkey", vkey);
        form.put("remark", StrUtil.nullToEmpty(remark));
        form.put("config_conn_allow", "false");
        form.put("compress", "false");
        form.put("crypt", "false");
        JsonNode node = post(s, "/client/add/", form);
        JsonNode id = node.get("id");
        if (id == null || id.asInt() <= 0) {
            throw new BusinessException("新建 nps 客户端失败：" + node);
        }
        return id.asInt();
    }

    public void deleteClient(TunnelSetting s, int clientId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("id", String.valueOf(clientId));
        post(s, "/client/del/", form);
    }

    // ------------------------------------------------------------------ 域名解析

    /**
     * 新增域名解析：把 host 指向该客户端上的 127.0.0.1:port。
     */
    public int addHost(TunnelSetting s, int clientId, String host, String target, String scheme, String remark) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", String.valueOf(clientId));
        form.put("host", host);
        form.put("scheme", StrUtil.blankToDefault(scheme, "http"));
        form.put("target", target);
        form.put("remark", StrUtil.nullToEmpty(remark));
        JsonNode node = post(s, "/index/addhost/", form);
        JsonNode id = node.get("id");
        if (id == null || id.asInt() <= 0) {
            throw new BusinessException("新增域名解析失败：" + node);
        }
        return id.asInt();
    }

    public void editHost(TunnelSetting s, int hostId, int clientId, String host, String target, String scheme, String remark) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("id", String.valueOf(hostId));
        form.put("client_id", String.valueOf(clientId));
        form.put("host", host);
        form.put("scheme", StrUtil.blankToDefault(scheme, "http"));
        form.put("target", target);
        form.put("remark", StrUtil.nullToEmpty(remark));
        post(s, "/index/edithost/", form);
    }

    public void deleteHost(TunnelSetting s, int hostId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("id", String.valueOf(hostId));
        post(s, "/index/delhost/", form);
    }

    public JsonNode listHosts(TunnelSetting s, int clientId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", String.valueOf(clientId));
        form.put("search", "");
        form.put("offset", "0");
        form.put("limit", "500");
        return post(s, "/index/hostlist/", form);
    }

    // ------------------------------------------------------------------ 基础请求

    public JsonNode post(TunnelSetting s, String path, Map<String, String> form) {
        long ts = serverTime(s);
        Map<String, String> all = new LinkedHashMap<>();
        all.put("auth_key", sign(s.getAuthKey(), ts));
        all.put("timestamp", String.valueOf(ts));
        all.putAll(form);

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(s) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        String resp = send(request);
        return read(resp);
    }

    private String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return send(request);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 302 || resp.statusCode() == 301) {
                throw new BusinessException("nps 拒绝访问（鉴权失败或未登录）");
            }
            if (resp.statusCode() != 200) {
                throw new BusinessException("nps 返回异常状态码：" + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("调用 nps API 失败：" + e.getMessage(), e);
        }
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(StrUtil.nullToEmpty(body));
        } catch (Exception e) {
            throw new BusinessException("解析 nps 响应失败：" + StrUtil.maxLength(body, 200), e);
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(StrUtil.nullToEmpty(v), StandardCharsets.UTF_8);
    }

}
