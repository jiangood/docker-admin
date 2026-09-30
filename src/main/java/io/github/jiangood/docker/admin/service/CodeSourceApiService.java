package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.CodeSourceAuthType;
import io.github.jiangood.docker.admin.entity.CodeSourceType;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码源平台 API 对接：按类型分派，目前实现 GitLab 的仓库列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSourceApiService {

    private final CodeSourceService codeSourceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 列出代码源上的仓库（供选择器使用），返回分页结构以适配前端 ProTable。
     */
    public Page<Map<String, Object>> listProjects(String codeSourceId, String search, Pageable pageable) {
        CodeSource source = codeSourceService.findById(codeSourceId)
                .orElseThrow(() -> new BusinessException("代码源不存在"));
        CodeSourceType type = source.getType() == null ? CodeSourceType.CUSTOM : source.getType();
        Assert.isTrue(type == CodeSourceType.GITLAB, "该代码源类型暂不支持自动列出仓库，请手动填写仓库地址");

        String base = StrUtil.removeSuffix(StrUtil.trimToEmpty(source.getUrl()), "/");
        Assert.hasText(base, "代码源地址不能为空");

        String apiUrl = base + "/api/v4/projects"
                + "?membership=true&order_by=last_activity_at&sort=desc"
                + "&per_page=" + pageable.getPageSize()
                + "&page=" + (pageable.getPageNumber() + 1)
                + (StrUtil.isBlank(search) ? "" : "&search=" + encode(search));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        applyAuth(builder, source);

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("请求代码源失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new BusinessException("请求代码源失败：" + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("代码源接口返回 " + response.statusCode() + "：" + StrUtil.maxLength(response.body(), 200));
        }

        long total = response.headers().firstValue("X-Total").map(CodeSourceApiService::parseLong).orElse(0L);
        List<Map<String, Object>> content = parseProjects(response.body(), base);
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * 在 GitLab 项目上创建指向 hookUrl 的 Webhook；若已存在相同地址的 Webhook 则直接复用。
     *
     * @return GitLab 侧的 Webhook id
     */
    public String enableProjectHook(CodeSource source, String projectPath, String hookUrl) {
        String base = requireGitLabBase(source);
        String hooksUrl = base + "/api/v4/projects/" + encode(projectPath) + "/hooks";

        JsonNode existing = request(source, "GET", hooksUrl, null, false);
        if (existing != null && existing.isArray()) {
            for (JsonNode hook : existing) {
                if (hookUrl.equals(text(hook, "url"))) {
                    String id = text(hook, "id");
                    log.info("GitLab 项目 {} 已存在 Webhook {}，复用 id={}", projectPath, hookUrl, id);
                    return id;
                }
            }
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("url", hookUrl);
        form.put("push_events", "true");
        form.put("tag_push_events", "true");
        JsonNode created = request(source, "POST", hooksUrl, form, false);
        String id = created == null ? null : text(created, "id");
        Assert.hasText(id, "创建 Webhook 失败：代码源未返回 Webhook id");
        log.info("已在 GitLab 项目 {} 创建 Webhook {}，id={}", projectPath, hookUrl, id);
        return id;
    }

    /**
     * 删除 GitLab 项目上的 Webhook。hookId 为空时静默忽略，接口返回 404 视为已删除。
     */
    public void disableProjectHook(CodeSource source, String projectPath, String hookId) {
        if (StrUtil.isBlank(hookId)) {
            return;
        }
        String base = requireGitLabBase(source);
        String url = base + "/api/v4/projects/" + encode(projectPath) + "/hooks/" + encode(hookId);
        request(source, "DELETE", url, null, true);
        log.info("已删除 GitLab 项目 {} 的 Webhook id={}", projectPath, hookId);
    }

    private String requireGitLabBase(CodeSource source) {
        CodeSourceType type = source.getType() == null ? CodeSourceType.CUSTOM : source.getType();
        Assert.isTrue(type == CodeSourceType.GITLAB, "仅支持 GitLab 类型代码源自动配置 Webhook");
        String base = StrUtil.removeSuffix(StrUtil.trimToEmpty(source.getUrl()), "/");
        Assert.hasText(base, "代码源地址不能为空");
        return base;
    }

    /**
     * 从 git 地址解析 GitLab 项目路径（形如 group/project），用于拼接 API 路径。
     */
    public static String projectPath(CodeSource source, String gitUrl) {
        String url = StrUtil.trimToEmpty(gitUrl);
        Assert.hasText(url, "代码仓库地址不能为空");
        String base = StrUtil.removeSuffix(StrUtil.trimToEmpty(source.getUrl()), "/");
        String path = StrUtil.isNotBlank(base) && url.startsWith(base) ? url.substring(base.length()) : stripHost(url);
        path = StrUtil.removePrefix(path, "/");
        path = StrUtil.removeSuffix(path, ".git");
        Assert.hasText(path, "无法从代码仓库地址解析项目路径：" + gitUrl);
        return path;
    }

    private static String stripHost(String url) {
        if (url.contains("://")) {
            try {
                String path = new URI(url).getPath();
                return path == null ? "" : path;
            } catch (URISyntaxException e) {
                return "";
            }
        }
        // scp 形式 user@host:path
        int colon = url.lastIndexOf(':');
        return colon >= 0 ? url.substring(colon + 1) : url;
    }

    /**
     * 按访问方式设置平台 API 认证头：访问令牌用 PRIVATE-TOKEN，账号密码用 Basic。
     */
    private static void applyAuth(HttpRequest.Builder builder, CodeSource source) {
        CodeSourceAuthType authType = source.effectiveAuthType();
        Assert.isTrue(authType != CodeSourceAuthType.SSH_KEY,
                "SSH 私钥方式无法调用平台 API，请把代码源的访问方式改为访问令牌或账号密码");

        if (authType == CodeSourceAuthType.PASSWORD) {
            Assert.hasText(source.getUsername(), "账号密码方式请填写用户名");
            String raw = source.getUsername() + ":" + StrUtil.nullToEmpty(source.getPassword());
            String basic = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
            return;
        }

        String token = source.apiToken();
        if (StrUtil.isNotBlank(token)) {
            builder.header("PRIVATE-TOKEN", token);
        }
    }

    private JsonNode request(CodeSource source, String method, String url, Map<String, String> form, boolean ignoreNotFound) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        applyAuth(builder, source);
        if (form == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
            builder.method(method, HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("请求代码源失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new BusinessException("请求代码源失败：" + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (ignoreNotFound && status == 404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException("代码源接口返回 " + status + "：" + StrUtil.maxLength(response.body(), 200));
        }
        if (StrUtil.isBlank(response.body())) {
            return null;
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BusinessException("解析代码源返回失败：" + e.getMessage(), e);
        }
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private List<Map<String, Object>> parseProjects(String body, String base) {
        List<Map<String, Object>> list = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BusinessException("解析代码源返回失败：" + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            return list;
        }
        for (JsonNode node : root) {
            String pathWithNamespace = text(node, "path_with_namespace");
            if (StrUtil.isBlank(pathWithNamespace)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", pathWithNamespace);
            item.put("name", text(node, "name_with_namespace"));
            item.put("defaultBranch", text(node, "default_branch"));
            item.put("lastActivityAt", text(node, "last_activity_at"));
            item.put("webUrl", text(node, "web_url"));
            // 用代码源地址拼接，保证与代码源主机一致，后续 clone/tag 才能匹配到
            item.put("gitUrl", base + "/" + pathWithNamespace + ".git");
            list.add(item);
        }
        return list;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
