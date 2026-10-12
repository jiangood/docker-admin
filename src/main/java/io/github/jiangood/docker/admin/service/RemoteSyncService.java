package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.RemoteRegistryQuery;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.entity.ImageRepo;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.registry.RegistryV2Client;
import io.github.jiangood.docker.admin.service.registry.RemoteImageMeta;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 从系统已配置的镜像注册中心补录镜像：把注册中心里已有的镜像仓库与版本读取到本平台的
 * {@code t_image_repo} / {@code t_image_tag}。
 * <p>
 * 走标准 Docker Registry V2 协议（{@link RegistryV2Client}），不需要云厂商 SDK。
 * 地址与凭据一律取 {@link RegistryService#getEffective()}——也就是「系统设置 → 镜像注册中心」
 * 里保存的那一条，因此调用方无需（也无法）再传凭据。
 * <p>
 * 两个入口对应两个页面，职责不同：
 * <ul>
 *     <li>镜像仓库<b>列表页</b>：{@link #catalog} 列出可补录的仓库，{@link #sync} 把选中的仓库连同版本一起补录；</li>
 *     <li>镜像仓库<b>详情页</b>：{@link #syncTags} 只把该仓库下新增的版本补进来，不新建仓库、不需要填地址。</li>
 * </ul>
 * <p>
 * <b>能力边界：</b>各注册中心对仓库列举（{@code /v2/_catalog}）的开放程度不同。阿里云个人版不开放，
 * 此时只能手工提供镜像地址；本服务对此做了降级处理，不会直接报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteSyncService {

    /**
     * 同时处理的仓库数。每个仓库内部顺序拉版本，避免并发过高打爆注册中心。
     */
    private static final int CONCURRENCY = 4;

    private final ImageRepoService imageRepoService;

    private final ImageTagService imageTagService;

    private final RegistryService registryService;

    // ------------------------------------------------------------------ 列表页：从注册中心导入仓库

    /**
     * 尝试列举注册中心下的全部仓库，并标出哪些已经导入过。
     * <p>
     * 不支持时不报错，返回 {@code supported=false} 与说明文案，由前端降级为手工输入镜像地址。
     */
    public Map<String, Object> catalog(RemoteRegistryQuery query) {
        Registry registry = requireRegistry();
        String host = hostOf(registry);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registry", registryInfo(registry));

        RegistryV2Client client = client(registry);
        if (!client.supportsCatalog()) {
            result.put("supported", false);
            result.put("repos", List.of());
            result.put("existing", List.of());
            result.put("message", "该注册中心未开放仓库列举接口（阿里云个人版即如此），请直接输入镜像地址");
            return result;
        }

        String prefix = namespacePrefix(registry.getNamespace());
        List<String> repos = client.listRepos(100, maxRepos(query));
        List<String> filtered = repos.stream()
                .filter(r -> StrUtil.isBlank(prefix) || r.startsWith(prefix))
                .toList();
        Set<String> local = filtered.isEmpty() ? Set.of() : localImages(host, prefix);
        result.put("supported", true);
        result.put("repos", filtered);
        result.put("existing", filtered.stream()
                .filter(r -> local.contains(normalize(r, host).toLowerCase(Locale.ROOT)))
                .toList());
        result.put("message", StrUtil.isBlank(prefix)
                ? null
                : "共 " + repos.size() + " 个仓库，命名空间 " + prefix + " 下有 " + filtered.size() + " 个");
        return result;
    }

    /**
     * 导入镜像仓库：把选中的仓库连同其版本 upsert 进本平台。
     */
    public RemoteSyncResult sync(RemoteRegistryQuery query) {        Registry registry = requireRegistry();
        RemoteSyncResult result = new RemoteSyncResult();
        for (RepoGroup group : groups(registry, query, query.isWithMeta())) {
            persistRepo(registry, group, result);
        }
        return result;
    }

    // ------------------------------------------------------------------ 详情页：只同步版本

    /**
     * 同步单个仓库的版本（详情页「同步标签」）。
     * <p>
     * 仓库必须是本地已有的，跨注册中心的仓库直接拒绝——系统只认「镜像注册中心」里配的那一个。
     */
    public RemoteSyncResult syncTags(String imageUrl, boolean withMeta, int maxTags) {
        Registry registry = requireRegistry();
        Assert.hasText(imageUrl, "镜像地址不能为空");
        String host = hostOf(registry);
        Assert.isTrue(StrUtil.equalsIgnoreCase(host, registryHost(imageUrl)),
                "该镜像仓库不属于已配置的注册中心 " + host + "，无法同步版本");

        RepoGroup group = groupOf(registry, imageUrl, withMeta, maxTags);
        Assert.state(StrUtil.isBlank(group.getError()), "读取远程版本失败：" + group.getError());

        RemoteSyncResult result = new RemoteSyncResult();
        // 仓库本地已存在，这里只做计数，不新建
        result.setRepoSkipped(1);
        persistTags(group, result);
        return result;
    }

    // ------------------------------------------------------------------ 内部：读远端

    private List<RepoGroup> groups(Registry registry, RemoteRegistryQuery query, boolean withMeta) {
        List<String> urls = targetUrls(query);
        if (urls.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(CONCURRENCY, urls.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RepoGroup>> futures = new ArrayList<>(urls.size());
            for (String url : urls) {
                futures.add(pool.submit(() -> groupOf(registry, url, withMeta, query.getMaxTags())));
            }
            List<RepoGroup> groups = new ArrayList<>(urls.size());
            for (Future<RepoGroup> f : futures) {
                groups.add(f.get());
            }
            return groups;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取远程仓库被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("读取远程仓库失败：" + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 读取单个仓库的版本列表。
     */
    private RepoGroup groupOf(Registry registry, String rawUrl, boolean withMeta, int maxTags) {
        String host = hostOf(registry);
        String imageUrl = normalize(rawUrl, host);
        RepoGroup group = new RepoGroup();
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
            log.warn("读取远程仓库 {} 失败：{}", imageUrl, e.getMessage());
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
        } catch (Exception e) {
            log.info("读取镜像 {}:{} 元数据失败：{}", repo, vo.getTag(), e.getMessage());
        }
    }

    /**
     * 本次要处理的镜像地址：优先用调用方传入的，没传则尝试自动发现。
     */
    private List<String> targetUrls(RemoteRegistryQuery query) {
        Set<String> urls = new LinkedHashSet<>();
        if (query.getImageUrls() != null) {
            for (String url : query.getImageUrls()) {
                if (StrUtil.isNotBlank(url)) {
                    urls.add(StrUtil.trim(url));
                }
            }
        }
        if (!urls.isEmpty()) {
            return new ArrayList<>(urls);
        }
        // 没填地址：尝试自动列举，不支持就返回空列表（调用方给出提示）
        Object repos = catalog(query).get("repos");
        if (repos instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(urls::add);
        }
        return new ArrayList<>(urls);
    }

    // ------------------------------------------------------------------ 内部：落库

    /**
     * 补录一个仓库：本地没有才新建，连同版本一起写入。
     * <p>
     * 仓库只在本地不存在时才 {@code save()}——{@code @LastModifiedDate} 会刷新 {@code updateTime}，
     * 对已有仓库反复保存会把「最近更新」排序打乱。
     * <p>
     * 这里没有加 {@code @Transactional}：本方法由同类内的 {@link #sync} 直接调用，走不到 Spring 代理，
     * 加了也不生效。写入本身是逐条 {@code save()}，每条自带 Spring Data 的事务，单条失败不会脏掉整批。
     */
    private void persistRepo(Registry registry, RepoGroup group, RemoteSyncResult result) {
        if (StrUtil.isNotBlank(group.getError())) {
            result.getFailures().add(group.getImageUrl() + "：" + group.getError());
            return;
        }
        if (imageRepoService.findByImageUrl(group.getImageUrl()) == null) {
            imageRepoService.upsert(group.getImageUrl(), nameOf(group.getImageUrl()),
                    "REMOTE", registry.getId(), null);
            result.setRepoAdded(result.getRepoAdded() + 1);
        } else {
            result.setRepoSkipped(result.getRepoSkipped() + 1);
        }
        persistTags(group, result);
    }

    /**
     * 写入版本：已存在的跳过，新增的补齐来源与元数据。
     */
    private void persistTags(RepoGroup group, RemoteSyncResult result) {
        List<RemoteTag> tags = group.getTags();
        if (tags == null) {
            return;
        }
        for (RemoteTag vo : tags) {
            if (vo.isExists()) {
                result.setTagSkipped(result.getTagSkipped() + 1);
                continue;
            }
            RemoteImageMeta meta = new RemoteImageMeta(vo.getBuildTime(), vo.getDigest(), vo.getSize());
            var saved = imageTagService.upsertRemote(group.getImageUrl(), vo.getTag(), meta);
            result.setTagAdded(result.getTagAdded() + 1);
            if (saved != null && (saved.getBuildTime() != null || saved.getSize() != null)) {
                result.setMetaFilled(result.getMetaFilled() + 1);
            }
        }
    }

    // ------------------------------------------------------------------ 内部：注册中心

    /**
     * 读取系统里配置生效的注册中心，未配置时给出明确指引。
     */
    private Registry requireRegistry() {
        Registry registry = registryService.getEffective();
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

    /**
     * 回给前端展示用的注册中心信息（不含密码）。
     */
    private Map<String, String> registryInfo(Registry registry) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("url", StrUtil.nullToEmpty(registry.getUrl()));
        info.put("namespace", StrUtil.nullToEmpty(registry.getNamespace()));
        info.put("username", StrUtil.nullToEmpty(registry.getUsername()));
        return info;
    }

    /**
     * 命名空间过滤前缀，末尾补斜杠；没配命名空间时不过滤。
     */
    private static String namespacePrefix(String namespace) {
        String v = StrUtil.trimToEmpty(namespace);
        return StrUtil.isBlank(v) ? "" : StrUtil.removeSuffix(v, "/") + "/";
    }

    /**
     * 本地已存在的镜像地址（小写），用于标记哪些远程仓库已经导入过。
     */
    private Set<String> localImages(String host, String prefix) {
        return imageRepoService.findAll(
                        Spec.<ImageRepo>of().rightLike("imageUrl", StrUtil.nullToEmpty(host) + "/" + prefix),
                        Sort.unsorted()).stream()
                .map(ImageRepo::getImageUrl)
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static int maxRepos(RemoteRegistryQuery query) {
        return query == null ? 500 : query.getMaxRepos();
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
     * 把用户填的地址补全成 {@code registry.host/namespace/name} 形式（与 {@code t_image_repo.imageUrl} 一致）。
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
     * 从镜像地址里取仓库相对路径：{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx} -> {@code ztcn/nginx}。
     */
    static String repoOf(String imageUrl, String host) {
        String v = StrUtil.trimToEmpty(imageUrl);
        if (StrUtil.isNotBlank(host) && StrUtil.startWithIgnoreCase(v, host)) {
            v = v.substring(host.length());
        }
        return StrUtil.removePrefix(v, "/");
    }

    /**
     * 仓库名取地址最后一段：{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx} -> {@code nginx}。
     */
    static String nameOf(String imageUrl) {
        String v = StrUtil.trimToEmpty(imageUrl);
        int idx = v.lastIndexOf('/');
        return idx < 0 ? v : v.substring(idx + 1);
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
    }

    /**
     * 读回来的单个仓库及其版本，按 imageUrl 分组。
     */
    @Data
    private static class RepoGroup {
        private String imageUrl;
        private String error;
        private List<RemoteTag> tags;
    }

}