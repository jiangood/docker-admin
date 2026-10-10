package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import io.github.jiangood.docker.admin.dao.ImageTagRepository;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.docker.admin.util.VersionUtils;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 镜像标签服务：读取并缓存镜像（Dockerfile）中声明的端口与卷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageTagService extends BaseService<ImageTag> {

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
            log.info("本地不存在镜像 {}，无法读取声明", fullName);
            return null;
        } catch (Exception e) {
            log.warn("读取镜像声明失败 {}: {}", fullName, e.getMessage());
            return null;
        }

        List<String> ports = parsePorts(response.getConfig());
        List<String> volumes = parseVolumes(response.getConfig());
        log.info("镜像 {} 声明端口 {}，卷 {}", fullName, ports, volumes);

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
