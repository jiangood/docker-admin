package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用升级 Webhook：外部 CI/CD 推送新镜像后调用，平台拉取镜像并重新部署应用。
 * <p>
 * 与项目构建的 webhook 同一套思路：路径 {@code admin/public/**} 免登录，鉴权依赖 URL 里的随机 token。
 * 请求体可选，支持 {@code {"tag":"1.2.3","forcePull":true}}，也兼容项目 webhook 的 {@code ref} / {@code tag_name}；
 * 不传 tag 时沿用应用当前版本，只做一次强制拉取后的重新部署。
 */
@RestController
@Slf4j
@RequestMapping("admin/public/app-webhook")
public class AppWebhookController {

    private static final String TAG_PREFIX = "refs/tags/";
    private static final String HEAD_PREFIX = "refs/heads/";
    private static final String[] TAG_FIELDS = {"tag", "tag_name", "imageTag", "version"};

    @Resource
    private AppService appService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("{token}")
    public AjaxResult trigger(@PathVariable String token,
                              @RequestParam(required = false) String tag,
                              @RequestParam(required = false) Boolean forcePull,
                              @RequestBody(required = false) String body) {
        JsonNode root = parse(body);

        // 与项目构建 webhook 一致：删除分支 / 标签不算一次升级
        if (isDeletion(root)) {
            log.info("应用 Webhook 忽略删除事件。body={}", body);
            return AjaxResult.ok().msg("ignored");
        }

        String targetTag = StrUtil.blankToDefault(tag, tagFromBody(root));
        // 分支推送不升级应用：避免把 CI 的仓库 Webhook 误配到这里时反复部署当前版本
        if (StrUtil.isBlank(targetTag) && isBranchRef(root)) {
            log.info("应用 Webhook 忽略分支推送。body={}", body);
            return AjaxResult.ok().msg("ignored");
        }

        Boolean pull = forcePull != null ? forcePull : booleanFromBody(root);

        String message = appService.triggerByWebhookToken(token, targetTag, pull);
        return AjaxResult.ok().msg(message);
    }

    /**
     * 是否为删除分支 / 标签的推送：显式 deleted 标记，或 after 为全 0 的 SHA。
     */
    private static boolean isDeletion(JsonNode root) {
        if (root == null) {
            return false;
        }
        if (root.path("deleted").asBoolean(false)) {
            return true;
        }
        String after = text(root, "after");
        return after != null && after.chars().allMatch(c -> c == '0');
    }

    private static boolean isBranchRef(JsonNode root) {
        String ref = text(root, "ref");
        return ref != null && ref.startsWith(HEAD_PREFIX);
    }

    private JsonNode parse(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("应用 Webhook body 不是合法 JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从请求体解析目标版本：优先普通 tag 字段，其次识别 {@code refs/tags/xxx}（忽略分支推送）。
     * 没有版本信息时返回 null，由 service 沿用当前版本。
     */
    private static String tagFromBody(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String field : TAG_FIELDS) {
            String value = text(root, field);
            if (value != null) {
                return value;
            }
        }
        String ref = text(root, "ref");
        if (ref != null && ref.startsWith(TAG_PREFIX)) {
            return StrUtil.trimToNull(ref.substring(TAG_PREFIX.length()));
        }
        return null;
    }

    /**
     * 是否强制拉取镜像。默认 true：webhook 通常由镜像推送触发，必须拿到仓库里的新内容。
     */
    private static Boolean booleanFromBody(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String field : new String[]{"forcePull", "force_pull"}) {
            JsonNode node = root.get(field);
            if (node != null && (node.isBoolean() || node.isTextual())) {
                return node.asBoolean();
            }
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && node.isTextual()) {
            String value = node.asText();
            return StrUtil.trimToNull(value);
        }
        return null;
    }
}
