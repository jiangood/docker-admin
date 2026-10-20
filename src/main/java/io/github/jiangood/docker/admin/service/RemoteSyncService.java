package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.registry.RegistryV2Client;
import io.github.jiangood.docker.admin.service.registry.RemoteImageMeta;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 从系统已配置的镜像注册中心同步镜像版本（镜像详情页「同步标签」）。
 * <p>
 * 走标准 Docker Registry V2 协议（{@link RegistryV2Client}），不需要云厂商 SDK。
 * 地址与凭据一律取 {@link RegistryService#getEffective()}——也就是「系统设置 → 镜像注册中心」
 * 里保存的那一条，因此调用方无需（也无法）再传凭据。
 * <p>
 * 镜像列表页的「补录镜像」已改为手工录入（{@link ImageManualService}），不再列举或拉取远程镜像。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteSyncService {

    private final ImageTagService imageTagService;

    private final RegistryService registryService;

    // ------------------------------------------------------------------ 详情页：只同步版本

    /**
     * 同步单个镜像的版本（详情页「同步标签」）。
     * <p>
     * 镜像必须是本地已有的，跨注册中心的镜像直接拒绝——系统只认「镜像注册中心」里配的那一个。
     */
    public RemoteSyncResult syncTags(String imageUrl, boolean withMeta, int maxTags, String registryId) {
        Registry registry = requireRegistry(registryId);
        Assert.hasText(imageUrl, "镜像地址不能为空");
        String host = hostOf(registry);
        Assert.isTrue(StrUtil.equalsIgnoreCase(host, registryHost(imageUrl)),
                "该镜像不属于已配置的注册中心 " + host + "，无法同步版本");

        ImageGroup group = groupOf(registry, imageUrl, withMeta, maxTags);
        Assert.state(StrUtil.isBlank(group.getError()), "读取远程版本失败：" + group.getError());

        RemoteSyncResult result = new RemoteSyncResult();
        // 镜像本地已存在，这里只做计数，不新建
        result.setImageSkipped(1);
        persistTags(group, result);
        return result;
    }

    // ------------------------------------------------------------------ 内部：读远端

    /**
     * 读取单个镜像的版本列表。
     */
    private ImageGroup groupOf(Registry registry, String rawUrl, boolean withMeta, int maxTags) {
        String host = hostOf(registry);
        String imageUrl = normalize(rawUrl, host);
        ImageGroup group = new ImageGroup();
        group.setImageUrl(imageUrl);
        try {
            RegistryV2Client client = client(registry);
            String repo = repoOf(imageUrl, host);
            List<String> tags = client.listTags(repo, 100, maxTags);
            Set<String> existing = imageTagService.tagSet(imageUrl);

            List<RemoteTag> list = new ArrayList<>(tags.size());
            for (String tag : tags) {
                RemoteTag vo = new RemoteTag();
                vo.setTag(tag);
                vo.setExists(existing.contains(tag));
                if (withMeta) {
                    fillMeta(client, repo, vo);
                }
                list.add(vo);
            }
            group.setTags(list);
        } catch (Exception e) {
            log.warn("读取远程镜像 {} 失败：{}", imageUrl, e.getMessage());
            group.setError(e.getMessage());
        }
        return group;
    }

    /**
     * 补齐单个版本的元数据；失败只记在该版本上，不影响其余版本。
     */
    private void fillMeta(RegistryV2Client client, String repo, RemoteTag vo) {
        try {
            RemoteImageMeta meta = client.fetchMeta(repo, vo.getTag());
            if (meta == null) {
                return;
            }
            vo.setBuildTime(meta.buildTime());
            vo.setSize(meta.size());
            vo.setDigest(meta.digest());
            vo.setExposedPorts(meta.exposedPorts());
            vo.setVolumes(meta.volumes());
        } catch (Exception e) {
            log.info("读取镜像 {}:{} 元数据失败：{}", repo, vo.getTag(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部：落库

    /**
     * 写入版本：新增的登记并补齐来源与元数据；已存在的只补缺失的元数据，不重复计数。
     */
    private void persistTags(ImageGroup group, RemoteSyncResult result) {
        List<RemoteTag> tags = group.getTags();
        if (tags == null) {
            return;
        }
        for (RemoteTag vo : tags) {
            RemoteImageMeta meta = new RemoteImageMeta(vo.getBuildTime(), vo.getDigest(), vo.getSize(),
                    vo.getExposedPorts(), vo.getVolumes());
            // 远程没读到任何元数据（如关闭了 withMeta）时，已存在的版本无需重写
            if (vo.isExists() && vo.getBuildTime() == null && vo.getSize() == null
                    && StrUtil.isBlank(vo.getDigest())) {
                result.setTagSkipped(result.getTagSkipped() + 1);
                continue;
            }
            // upsertRemote 只在远程读到值时才覆盖，因此对已存在的版本是「补齐空字段」，
            // 可修好历史上登记时没记下构建时间/大小的数据
            var saved = imageTagService.upsertRemote(group.getImageUrl(), vo.getTag(), meta);
            if (vo.isExists()) {
                result.setTagSkipped(result.getTagSkipped() + 1);
            } else {
                result.setTagAdded(result.getTagAdded() + 1);
            }
            boolean metaFilled = saved != null && (saved.getBuildTime() != null || saved.getSize() != null
                    || (saved.getExposedPorts() != null && !saved.getExposedPorts().isEmpty())
                    || (saved.getVolumes() != null && !saved.getVolumes().isEmpty()));
            if (metaFilled) {
                result.setMetaFilled(result.getMetaFilled() + 1);
            }
        }
    }

    // ------------------------------------------------------------------ 内部：注册中心

    /**
     * 读取系统里配置生效的注册中心，未配置时给出明确指引。
     */
    private Registry requireRegistry(String registryId) {
        Registry registry = registryService.requireRegistry(registryId);
        Assert.notNull(registry, "尚未配置镜像注册中心，请先到「系统设置 → 镜像注册中心」配置");
        Assert.hasText(registry.getUrl(), "镜像注册中心的地址未配置，请先到「系统设置 → 镜像注册中心」配置");
        return registry;
    }

    private RegistryV2Client client(Registry registry) {
        return new RegistryV2Client(registry.getUrl(), registry.getUsername(), registry.getPassword());
    }

    /**
     * 注册中心主机名，同时也是 {@code imageUrl} 的前缀。
     */
    private String hostOf(Registry registry) {
        return registryHost(registry.getUrl());
    }

    // ------------------------------------------------------------------ 地址处理

    /**
     * 取出注册中心主机名：{@code https://harbor.example.com/} -> {@code harbor.example.com}。
     */
    static String registryHost(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        String v = url.trim();
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            v = v.substring(scheme + 3);
        }
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(0, slash);
        }
        return StrUtil.isBlank(v) ? null : v;
    }

    /**
     * 把用户填的地址补全成 {@code registry.host/namespace/name} 形式（与 {@code t_image.imageUrl} 一致）。
     * <p>
     * 三种写法都能接受：{@code ztcn/nginx}、{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx}、
     * {@code https://registry.cn-hangzhou.aliyuncs.com/ztcn/nginx}。
     */
    static String normalize(String rawUrl, String host) {
        String v = StrUtil.trimToEmpty(rawUrl);
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            v = v.substring(scheme + 3);
        }
        if (StrUtil.isNotBlank(host) && StrUtil.startWithIgnoreCase(v, host)) {
            v = v.substring(host.length());
        }
        v = StrUtil.removePrefix(v, "/");
        v = StrUtil.removeSuffix(v, "/");
        if (StrUtil.isBlank(host)) {
            return v;
        }
        return StrUtil.isBlank(v) ? host : host + "/" + v;
    }

    /**
     * 从镜像地址里取镜像相对路径：{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx} -> {@code ztcn/nginx}。
     */
    static String repoOf(String imageUrl, String host) {
        String v = StrUtil.trimToEmpty(imageUrl);
        if (StrUtil.isNotBlank(host) && StrUtil.startWithIgnoreCase(v, host)) {
            v = v.substring(host.length());
        }
        return StrUtil.removePrefix(v, "/");
    }

    // ------------------------------------------------------------------ 内部数据结构

    /**
     * 读回来的单个版本，只在服务内部流转。
     */
    @Data
    private static class RemoteTag {
        private String tag;
        /** 本地是否已登记该版本。 */
        private boolean exists;
        /** 镜像构建时间，来自镜像配置的 created 字段。 */
        private LocalDateTime buildTime;
        /** 镜像压缩后总大小（字节）。 */
        private Long size;
        /** 镜像配置摘要。 */
        private String digest;
        /** 镜像声明的端口，形如 80/tcp。 */
        private List<String> exposedPorts;
        /** 镜像声明的卷路径，如 /data。 */
        private List<String> volumes;
    }

    /**
     * 读回来的单个镜像及其版本，按 imageUrl 分组。
     */
    @Data
    private static class ImageGroup {
        private String imageUrl;
        private String error;
        private List<RemoteTag> tags;
    }

}
