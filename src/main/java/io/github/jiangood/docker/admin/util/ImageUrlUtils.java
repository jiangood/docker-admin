package io.github.jiangood.docker.admin.util;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.Registry;

/**
 * 镜像地址工具：统一拼/拆「镜像url」（不带 tag 的镜像地址）与「完整地址」。
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
     * 注册中心仓库前缀：{@code registry.cn-hangzhou.aliyuncs.com/ztcn}（不含协议、不带尾斜杠）。
     * <p>
     * 用来和镜像地址做前缀匹配：同一个 host 下可以有多个命名空间/账号，只比 host 会带错凭据。
     */
    public static String repoBaseOf(Registry registry) {
        if (registry == null) {
            return null;
        }
        String base = withoutScheme(StrUtil.removeSuffix(StrUtil.nullToEmpty(registry.getFullUrl()), "/"));
        return StrUtil.isBlank(base) ? null : base;
    }

    /**
     * 去掉地址里的 {@code http(s)://} 前缀，便于把注册中心地址与镜像地址放在一起比较。
     */
    public static String withoutScheme(String url) {
        String v = StrUtil.trimToEmpty(url);
        int scheme = v.indexOf("://");
        return scheme >= 0 ? v.substring(scheme + 3) : v;
    }

    /**
     * 取地址里的注册中心主机名：{@code https://harbor.example.com:8443/ztcn/nginx} -> {@code harbor.example.com:8443}。
     * <p>
     * 遵循 docker 的解析规则：首段不含 {@code .} / {@code :} 且不是 {@code localhost} 时，
     * 该地址属于 Docker Hub（如 {@code ztcn/nginx}），没有显式注册中心，返回 null。
     */
    public static String hostOf(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        String v = url.trim();
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            v = v.substring(scheme + 3);
        }
        int slash = v.indexOf('/');
        String first = slash < 0 ? v : v.substring(0, slash);
        if (StrUtil.isBlank(first)) {
            return null;
        }
        return (first.contains(".") || first.contains(":") || "localhost".equals(first)) ? first : null;
    }

    /**
     * 从完整地址（repo:tag）中取镜像地址（不含 tag）；无 tag 时原样返回。
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
