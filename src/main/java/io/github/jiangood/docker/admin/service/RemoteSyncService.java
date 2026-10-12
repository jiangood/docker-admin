package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.RemoteRegistryQuery;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.dto.RemoteTagVo;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.service.registry.RegistryV2Client;
import io.github.jiangood.docker.admin.service.registry.RemoteImageMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 远程注册中心同步：把远端的镜像仓库与版本读取到本平台的 {@code t_image_repo} / {@code t_image_tag}。
 * <p>
 * 走标准 Docker Registry V2 协议（{@link RegistryV2Client}），不需要云厂商 SDK，
 * 凭据只在本次请求内使用、不落库。
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

    /**
     * 尝试列举注册中心下的全部仓库。
     * <p>
     * 不支持时不报错，返回 {@code supported=false} 与说明文案，由前端降级为手工输入镜像地址。
     */
    public Map<String, Object> catalog(RemoteRegistryQuery query) {
        Assert.hasText(query.getUrl(), "注册中心地址不能为空");
        RegistryV2Client client = new RegistryV2Client(query.getUrl(), query.getUsername(), query.getPassword());
        Map<String, Object> result = new LinkedHashMap<>();
        if (!client.supportsCatalog()) {
            result.put("supported", false);
            result.put("repos", List.of());
            result.put("message", "该注册中心未开放仓库列举接口（阿里云个人版即如此），请直接输入镜像地址");
            return result;
        }
        String namespace = StrUtil.trimToEmpty(query.getNamespace());
        final String prefix = StrUtil.isNotBlank(namespace) && !namespace.endsWith("/")
                ? namespace + "/"
                : namespace;
        List<String> repos = client.listRepos(100, query.getMaxRepos());
        List<String> filtered = repos.stream()
                .filter(r -> StrUtil.isBlank(prefix) || r.startsWith(prefix))
                .toList();
        result.put("supported", true);
        result.put("repos", filtered);
        result.put("message", StrUtil.isBlank(prefix)
                ? null
                : "共 " + repos.size() + " 个仓库，命名空间 " + prefix + " 下有 " + filtered.size() + " 个");
        return result;
    }

    /**
     * 预览：不落库，也不读镜像元数据（每个版本要 2 次请求，预览阶段固定跳过）。
     */
    public List<RemoteTagVo.RepoGroup> preview(RemoteRegistryQuery query) {
        return groups(query, false);
    }

    /**
     * 执行同步：把远端仓库与版本 upsert 进本平台。
     */
    public RemoteSyncResult sync(RemoteRegistryQuery query) {
        RemoteSyncResult result = new RemoteSyncResult();
        for (RemoteTagVo.RepoGroup group : groups(query, query.isWithMeta())) {
            persist(group, result);
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private List<RemoteTagVo.RepoGroup> groups(RemoteRegistryQuery query, boolean withMeta) {
        List<String> urls = targetUrls(query);
        if (urls.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(CONCURRENCY, urls.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RemoteTagVo.RepoGroup>> futures = new ArrayList<>(urls.size());
            for (String url : urls) {
                futures.add(pool.submit(() -> groupOf(url, query, withMeta)));
            }
            List<RemoteTagVo.RepoGroup> groups = new ArrayList<>(urls.size());
            for (Future<RemoteTagVo.RepoGroup> f : futures) {
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
    private RemoteTagVo.RepoGroup groupOf(String rawUrl, RemoteRegistryQuery query, boolean withMeta) {
        String host = registryHost(query.getUrl());
        String imageUrl = normalize(rawUrl, host);
        RemoteTagVo.RepoGroup group = new RemoteTagVo.RepoGroup();
        group.setImageUrl(imageUrl);
        try {
            RegistryV2Client client = new RegistryV2Client(query.getUrl(), query.getUsername(), query.getPassword());
            String repo = repoOf(imageUrl, host);
            List<String> tags = client.listTags(repo, 100, query.getMaxTags());
            Set<String> existing = imageTagService.tagSet(imageUrl);

            List<RemoteTagVo> list = new ArrayList<>(tags.size());
            for (String tag : tags) {
                RemoteTagVo vo = new RemoteTagVo();
                vo.setImageUrl(imageUrl);
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
    private void fillMeta(RegistryV2Client client, String repo, RemoteTagVo vo) {
        try {
            RemoteImageMeta meta = client.fetchMeta(repo, vo.getTag());
            if (meta == null) {
                return;
            }
            vo.setBuildTime(meta.buildTime());
            vo.setSize(meta.size());
            vo.setDigest(meta.digest());
        } catch (Exception e) {
            vo.setMetaError(e.getMessage());
        }
    }

    /**
     * 落库。
     * <p>
     * 仓库只在本地不存在时才新建——{@code save()} 会因 {@code @LastModifiedDate} 刷新
     * {@code updateTime}，对已有仓库反复保存会把「最近更新」排序打乱。
     * <p>
     * 这里没有加 {@code @Transactional}：本方法由同类内的 {@link #sync} 直接调用，走不到 Spring 代理，
     * 加了也不生效。写入本身是逐条 {@code save()}，每条自带 Spring Data 的事务，单条失败不会脏掉整批。
     */
    public void persist(RemoteTagVo.RepoGroup group, RemoteSyncResult result) {
        String imageUrl = group.getImageUrl();
        if (StrUtil.isNotBlank(group.getError())) {
            result.getFailures().add(imageUrl + "：" + group.getError());
            return;
        }
        List<RemoteTagVo> tags = group.getTags();
        if (tags == null) {
            return;
        }

        if (imageRepoService.findByImageUrl(imageUrl) == null) {
            imageRepoService.upsert(imageUrl, nameOf(imageUrl), "REMOTE", null, null);
            result.setRepoAdded(result.getRepoAdded() + 1);
        } else {
            result.setRepoSkipped(result.getRepoSkipped() + 1);
        }

        for (RemoteTagVo vo : tags) {
            if (vo.isExists()) {
                result.setTagSkipped(result.getTagSkipped() + 1);
                continue;
            }
            RemoteImageMeta meta = new RemoteImageMeta(vo.getBuildTime(), vo.getDigest(), vo.getSize());
            ImageTag saved = imageTagService.upsertRemote(vo.getImageUrl(), vo.getTag(), meta);
            result.setTagAdded(result.getTagAdded() + 1);
            if (saved != null && (saved.getBuildTime() != null || saved.getSize() != null)) {
                result.setMetaFilled(result.getMetaFilled() + 1);
            }
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

}