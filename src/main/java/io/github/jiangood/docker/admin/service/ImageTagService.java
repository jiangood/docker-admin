package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import io.github.jiangood.docker.admin.dao.ImageTagRepository;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.service.registry.RemoteImageMeta;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.docker.admin.util.VersionUtils;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 镜像标签服务：读取并缓存镜像（Dockerfile）中声明的端口与卷。
 */
@Service
@RequiredArgsConstructor
public class ImageTagService extends BaseService<ImageTag> {

    private static final Logger fileLog = FileLog.logger();

    /**
     * 可变的浮动版本号，取「最新版本」时排除。
     */
    private static final String LATEST_TAG = "latest";

    private final ImageTagRepository imageTagRepository;

    public Optional<ImageTag> find(String imageUrl, String tag) {
        if (StrUtil.isBlank(imageUrl) || StrUtil.isBlank(tag)) {
            return Optional.empty();
        }
        return imageTagRepository.findByImageUrlAndTag(imageUrl, tag);
    }

    public List<ImageTag> listByImageUrl(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return Collections.emptyList();
        }
        return imageTagRepository.findAllByImageUrl(imageUrl).stream()
                .sorted(Comparator.comparing(ImageTag::getTag, VersionUtils.VERSION_DESC))
                .toList();
    }

    /**
     * 某个镜像仓库的所有版本号（tag），版本倒序（如 v1.10.0 在 v1.9.0 之前，latest 在最后）。
     */
    public List<String> tags(String imageUrl) {
        return listByImageUrl(imageUrl).stream()
                .map(ImageTag::getTag)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .sorted(VersionUtils.VERSION_DESC)
                .toList();
    }

    /**
     * 某个镜像仓库已登记的版本号集合，用于远程同步时批量判断「已存在」。
     */
    public Set<String> tagSet(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return Collections.emptySet();
        }
        return imageTagRepository.findAllByImageUrl(imageUrl).stream()
                .map(ImageTag::getTag)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    /**
     * 批量取每个仓库的最新版本号，不含 latest。
     * <p>
     * 供镜像仓库列表展示「最新版本」用：一次查询覆盖整页仓库，避免逐行查询。
     */
    public Map<String, String> latestTagMap(Collection<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> tagsByUrl = new HashMap<>();
        for (ImageTag it : imageTagRepository.findAllByImageUrlIn(imageUrls)) {
            String tag = it.getTag();
            if (StrUtil.isBlank(tag) || LATEST_TAG.equalsIgnoreCase(tag)) {
                continue;
            }
            tagsByUrl.computeIfAbsent(it.getImageUrl(), k -> new ArrayList<>()).add(tag);
        }
        Map<String, String> result = new HashMap<>();
        tagsByUrl.forEach((url, tags) -> tags.stream()
                .sorted(VersionUtils.VERSION_DESC)
                .findFirst()
                .ifPresent(tag -> result.put(url, tag)));
        return result;
    }

    @Transactional
    public void deleteByImageUrl(String imageUrl) {
        if (StrUtil.isNotBlank(imageUrl)) {
            imageTagRepository.deleteByImageUrl(imageUrl);
        }
    }

    /**
     * 新增或更新一个 tag（不读取声明，供镜像同步使用）。
     */
    @Transactional
    public ImageTag upsert(String imageUrl, String tag, String source) {
        if (StrUtil.isBlank(imageUrl) || StrUtil.isBlank(tag)) {
            return null;
        }
        ImageTag it = imageTagRepository.findByImageUrlAndTag(imageUrl, tag).orElseGet(ImageTag::new);
        it.setImageUrl(imageUrl);
        it.setTag(tag);
        it.setFullUrl(ImageUrlUtils.full(imageUrl, tag));
        if (StrUtil.isNotBlank(source)) {
            it.setSource(source);
        }
        return imageTagRepository.save(it);
    }

    /**
     * 新增或更新一个远程读取到的 tag，并补齐构建时间、大小、摘要。
     * <p>
     * 来源只在原值为空时补成 REMOTE，避免覆盖本地构建/同步产生的来源标记。
     * 元数据里为空的字段保持原值不变，不用空值冲掉已有数据。
     */
    @Transactional
    public ImageTag upsertRemote(String imageUrl, String tag, RemoteImageMeta meta) {
        if (StrUtil.isBlank(imageUrl) || StrUtil.isBlank(tag)) {
            return null;
        }
        ImageTag it = imageTagRepository.findByImageUrlAndTag(imageUrl, tag).orElseGet(ImageTag::new);
        it.setImageUrl(imageUrl);
        it.setTag(tag);
        it.setFullUrl(ImageUrlUtils.full(imageUrl, tag));
        if (StrUtil.isBlank(it.getSource())) {
            it.setSource("REMOTE");
        }
        if (meta != null) {
            if (meta.buildTime() != null) {
                it.setBuildTime(meta.buildTime());
            }
            if (StrUtil.isNotBlank(meta.digest())) {
                it.setDigest(meta.digest());
            }
            if (meta.size() != null) {
                it.setSize(meta.size());
            }
        }
        return imageTagRepository.save(it);
    }

    /**
     * 读取镜像声明的端口与卷并保存。本地不存在该镜像时返回 null（不抛异常）。
     *
     * @param fullName 完整镜像地址（registry/namespace/name:tag），用于 inspect
     */
    public ImageTag inspect(DockerClient client, String fullName, String imageUrl, String tag,
                            String buildLogId, String source) {
        if (StrUtil.isBlank(imageUrl) || StrUtil.isBlank(tag) || StrUtil.isBlank(fullName)) {
            return null;
        }
        InspectImageResponse response;
        try {
            response = client.inspectImageCmd(fullName).exec();
        } catch (NotFoundException e) {
            fileLog.info("本地不存在镜像 {}，无法读取声明", fullName);
            return null;
        } catch (Exception e) {
            fileLog.warn("读取镜像声明失败 {}: {}", fullName, e.getMessage());
            return null;
        }

        List<String> ports = parsePorts(response.getConfig());
        List<String> volumes = parseVolumes(response.getConfig());
        fileLog.info("镜像 {} 声明端口 {}，卷 {}", fullName, ports, volumes);

        ImageTag it = imageTagRepository.findByImageUrlAndTag(imageUrl, tag).orElseGet(ImageTag::new);
        it.setImageUrl(imageUrl);
        it.setTag(tag);
        it.setFullUrl(ImageUrlUtils.full(imageUrl, tag));
        if (StrUtil.isNotBlank(source)) {
            it.setSource(source);
        }
        if (StrUtil.isNotBlank(buildLogId)) {
            it.setBuildLogId(buildLogId);
        }
        if (StrUtil.isNotBlank(response.getId())) {
            it.setDigest(response.getId());
        }
        if (response.getSize() != null) {
            it.setSize(response.getSize());
        }
        it.setExposedPorts(ports);
        it.setVolumes(volumes);
        it.setInspected(true);
        it.setInspectTime(LocalDateTime.now());
        return imageTagRepository.save(it);
    }

    private List<String> parsePorts(ContainerConfig config) {
        List<String> result = new ArrayList<>();
        if (config == null || config.getExposedPorts() == null) {
            return result;
        }
        for (ExposedPort p : config.getExposedPorts()) {
            result.add(p.getPort() + "/" + p.getProtocol().toString().toLowerCase());
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<String> parseVolumes(ContainerConfig config) {
        List<String> result = new ArrayList<>();
        if (config == null || config.getVolumes() == null) {
            return result;
        }
        result.addAll(config.getVolumes().keySet());
        result.sort(Comparator.naturalOrder());
        return result;
    }

}
