package io.github.jiangood.docker.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.service.ProjectService;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用 Git Webhook：推送 tag 时按 token 找到镜像并触发构建。
 * 路径 /admin/public/** 框架已放行，免登录；鉴权依赖 URL 中的随机 token。
 */
@RestController
@Slf4j
@RequestMapping("admin/public/webhook")
public class WebhookController {

    private static final String TAG_PREFIX = "refs/tags/";
    private static final String HEAD_PREFIX = "refs/heads/";

    private static final String[] TAG_FIELDS = {"tag_name", "tag", "ref_name"};

    @Resource
    private ProjectService projectService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("{token}")
    public AjaxResult trigger(@PathVariable String token, @RequestBody(required = false) String body) {
        String tag = extractTag(body);
        if (tag == null) {
            log.info("Webhook 未识别到 tag 推送，忽略。body={}", body);
            return AjaxResult.ok().msg("ignored");
        }
        projectService.triggerByToken(token, tag);
        return AjaxResult.ok().msg("构建已触发：" + tag);
    }

    /**
     * 通用解析：优先 ref（refs/tags/xxx），兼容 tag_name / tag / ref_name。
     * 分支推送（refs/heads/*）返回 null 表示忽略。
     */
    String extractTag(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Webhook body 不是合法 JSON: {}", e.getMessage());
            return null;
        }
        String ref = text(root, "ref");
        if (ref != null) {
            if (ref.startsWith(TAG_PREFIX)) {
                return ref.substring(TAG_PREFIX.length());
            }
            if (ref.startsWith(HEAD_PREFIX)) {
                return null;
            }
        }
        for (String field : TAG_FIELDS) {
            String value = text(root, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && node.isTextual()) {
            String value = node.asText();
            return value.isBlank() ? null : value.trim();
        }
        return null;
    }
}
