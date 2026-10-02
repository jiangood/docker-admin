package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.Registry;

/**
 * 镜像地址工具：统一拼/拆「镜像url」（不带 tag 的仓库地址）与「完整地址」。
 */
public final class ImageUrlUtils {

    private ImageUrlUtils() {
    }

    /**
     * 镜像url：registry.url/namespace/name；未配置注册中心或命名空间时仅返回 name。
     */
    public static String repoUrl(Registry registry, String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        if (registry == null) {
            return name;
        }
        String base = StrUtil.removeSuffix(StrUtil.nullToEmpty(registry.getFullUrl()), "/");
        if (StrUtil.isBlank(base)) {
            return name;
        }
        return base + "/" + name;
    }

    /**
     * 从完整地址（repo:tag）中取仓库地址（不含 tag）；无 tag 时原样返回。
     */
    public static String repoOf(String fullUrl) {
        if (StrUtil.isBlank(fullUrl)) {
            return fullUrl;
        }
        int lastSlash = fullUrl.lastIndexOf('/');
        int lastColon = fullUrl.lastIndexOf(':');
        if (lastColon > lastSlash) {
            return fullUrl.substring(0, lastColon);
        }
        return fullUrl;
    }

    /**
     * 从完整地址（repo:tag）中取 tag；无 tag 时返回 null。
     */
    public static String tagOf(String fullUrl) {
        if (StrUtil.isBlank(fullUrl)) {
            return null;
        }
        int lastSlash = fullUrl.lastIndexOf('/');
        int lastColon = fullUrl.lastIndexOf(':');
        if (lastColon > lastSlash) {
            return fullUrl.substring(lastColon + 1);
        }
        return null;
    }

    /**
     * 完整镜像地址：repo + ":" + tag。
     */
    public static String full(String repoUrl, String tag) {
        return repoUrl + ":" + tag;
    }

}
