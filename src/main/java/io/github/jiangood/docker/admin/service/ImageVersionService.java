package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import io.github.jiangood.docker.admin.dao.ImageVersionRepository;
import io.github.jiangood.docker.admin.entity.ImageVersion;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 镜像版本服务：读取并缓存镜像（Dockerfile）中声明的端口与卷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageVersionService extends BaseService<ImageVersion> {

    private final ImageVersionRepository imageVersionRepository;

    public Optional<ImageVersion> find(String imageId, String tag) {
        if (StrUtil.isBlank(imageId) || StrUtil.isBlank(tag)) {
            return Optional.empty();
        }
        return imageVersionRepository.findByImageIdAndTag(imageId, tag);
    }

    public List<ImageVersion> findByImageId(String imageId) {
        if (StrUtil.isBlank(imageId)) {
            return Collections.emptyList();
        }
        return imageVersionRepository.findAllByImageId(imageId);
    }

    public List<ImageVersion> findAllVersion() {
        return imageVersionRepository.findAll();
    }

    /**
     * 某个镜像的所有版本号（tag），倒序。
     */
    public List<String> tags(String imageId) {
        return findByImageId(imageId).stream()
                .map(ImageVersion::getTag)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    public void removeByImageId(String imageId) {
        if (StrUtil.isNotBlank(imageId)) {
            imageVersionRepository.deleteByImageId(imageId);
        }
    }

    /**
     * 读取镜像声明的端口与卷并保存。本地不存在该镜像时返回 null（不抛异常）。
     *
     * @param fullName 镜像完整地址（registry/namespace/name:tag），用于 inspect
     */
    public ImageVersion inspect(DockerClient client, String fullName, String imageId, String tag) {
        if (StrUtil.isBlank(imageId) || StrUtil.isBlank(tag) || StrUtil.isBlank(fullName)) {
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
        return saveDeclaration(imageId, tag, ports, volumes);
    }

    public ImageVersion saveDeclaration(String imageId, String tag, List<String> ports, List<String> volumes) {
        ImageVersion iv = find(imageId, tag).orElseGet(ImageVersion::new);
        iv.setImageId(imageId);
        iv.setTag(tag);
        iv.setExposedPorts(ports == null ? new ArrayList<>() : ports);
        iv.setVolumes(volumes == null ? new ArrayList<>() : volumes);
        iv.setInspected(true);
        iv.setInspectTime(LocalDateTime.now());
        return imageVersionRepository.save(iv);
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
