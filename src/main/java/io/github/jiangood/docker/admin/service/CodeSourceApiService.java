package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.CodeSource;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
        if (StrUtil.isNotBlank(source.getPassword())) {
            builder.header("PRIVATE-TOKEN", source.getPassword());
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

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("代码源接口返回 " + response.statusCode() + "：" + StrUtil.maxLength(response.body(), 200));
        }

        long total = response.headers().firstValue("X-Total").map(CodeSourceApiService::parseLong).orElse(0L);
        List<Map<String, Object>> content = parseProjects(response.body(), base);
        return new PageImpl<>(content, pageable, total);
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
