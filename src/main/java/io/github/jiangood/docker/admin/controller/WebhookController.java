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
 * 通用 Git Webhook：推送 tag 时构建对应版本；推送分支时，若项目开启开关且分支在配置列表内，构建分支最新代码。
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
        JsonNode root = parse(body);
        if (root == null) {
            return AjaxResult.ok().msg("ignored");
        }
        // 删除分支/标签不算一次构建
        if (isDeletion(root)) {
            log.info("Webhook 忽略删除事件。body={}", body);
            return AjaxResult.ok().msg("ignored");
        }

        String ref = text(root, "ref");
        String tag = null;
        String branch = null;
        if (ref != null) {
            if (ref.startsWith(TAG_PREFIX)) {
                tag = ref.substring(TAG_PREFIX.length());
            } else if (ref.startsWith(HEAD_PREFIX)) {
                branch = ref.substring(HEAD_PREFIX.length());
            }
        }
        // 兼容无 ref 的推送体：按 tag 字段解析
        if (tag == null && branch == null) {
            tag = firstText(root, TAG_FIELDS);
        }

        if (tag != null) {
            projectService.triggerByToken(token, tag);
            return AjaxResult.ok().msg("构建已触发：" + tag);
        }
        if (branch != null) {
            projectService.triggerBranchByToken(token, branch);
            return AjaxResult.ok().msg("构建已触发：" + branch);
        }
        log.info("Webhook 未识别到引用，忽略。body={}", body);
        return AjaxResult.ok().msg("ignored");
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Webhook body 不是合法 JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 是否为删除分支/标签的推送：显式 deleted 标记，或 after 为全 0 的 SHA。
     */
    private static boolean isDeletion(JsonNode root) {
        if (root.path("deleted").asBoolean(false)) {
            return true;
        }
        String after = text(root, "after");
        return after != null && after.chars().allMatch(c -> c == '0');
    }

    private static String firstText(JsonNode root, String[] fields) {
        for (String field : fields) {
            String value = text(root, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && node.isTextual()) {
            String value = node.asText();
            return value.isBlank() ? null : value.trim();
        }
        return null;
    }
}
