package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.BusinessException;

import java.util.regex.Pattern;

/**
 * 主机辅助容器镜像地址的约束。
 * <p>
 * 参数设置只保存“镜像地址”（不含 tag），每台主机部署时单独选择 tag，
 * 实际镜像 = 地址 + ":" + tag。这样后续 agent 增加功能时，只需要发布新 tag，
 * 各主机按需切换，不用反复修改全局地址。
 */
public final class AgentImageUtils {

    public static final String DEFAULT_REPOSITORY = "ghcr.io/jiangood/docker-admin-agent";
    public static final String DEFAULT_TAG = "latest";

    /**
     * Docker tag 规则：首字符字母/数字/下划线，其余字母/数字/下划线/点/短横线。
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$");

    /**
     * 允许 registry 端口（冒号出现在最后一个 / 之前），因此这里只做粗校验。
     */
    private static final Pattern REPOSITORY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]*$");

    private AgentImageUtils() {
    }

    /**
     * 归一化 tag：空值回落到 latest，并校验 Docker tag 格式。
     */
    public static String normalizeTag(String tag) {
        String value = StrUtil.trimToEmpty(tag);
        if (value.isEmpty()) {
            return DEFAULT_TAG;
        }
        if (!TAG_PATTERN.matcher(value).matches()) {
            throw new BusinessException("Agent 镜像 Tag 格式不正确，只允许字母、数字、下划线、点和短横线，例如 latest、v4.8.40");
        }
        return value;
    }

    /**
     * 归一化镜像地址：空值回落到默认地址，并拒绝带 tag/digest 的完整镜像。
     */
    public static String normalizeRepository(String repository) {
        String value = StrUtil.trimToEmpty(repository);
        if (value.isEmpty()) {
            return DEFAULT_REPOSITORY;
        }
        if (value.contains("@")) {
            throw new BusinessException("Agent 镜像地址不要包含 digest（@sha256:...），只填仓库地址");
        }
        if (!REPOSITORY_PATTERN.matcher(value).matches() || value.endsWith("/")) {
            throw new BusinessException("Agent 镜像地址格式不正确，例如 ghcr.io/jiangood/docker-admin-agent");
        }
        int lastSlash = value.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        if (lastSegment.contains(":")) {
            throw new BusinessException("Agent 镜像地址不要带 tag，tag 在每台主机部署时单独选择");
        }
        return value;
    }

    public static String resolveImage(String repository, String tag) {
        return normalizeRepository(repository) + ":" + normalizeTag(tag);
    }
}
