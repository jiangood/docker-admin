package io.github.jiangood.docker.admin.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import io.github.jiangood.docker.admin.dto.HostImageVo;
import io.github.jiangood.docker.admin.dto.HostInfoVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 主机级 Docker 查询：镜像列表、引擎信息。
 */
@Slf4j
@Service
public class HostDockerService {

    @Resource
    private DockerClientManager dockerClientManager;

    public List<HostImageVo> listImages(Host host) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<Image> images = client.listImagesCmd().withShowAll(true).exec();
            List<HostImageVo> result = new ArrayList<>(images.size());
            for (Image img : images) {
                HostImageVo vo = new HostImageVo();
                vo.setId(img.getId());
                vo.setIdShort(shortImageId(img.getId()));
                if (img.getRepoTags() != null) {
                    for (String t : img.getRepoTags()) {
                        vo.getRepoTags().add(t);
                    }
                }
                if (img.getRepoDigests() != null) {
                    for (String d : img.getRepoDigests()) {
                        vo.getRepoDigests().add(d);
                    }
                }
                vo.setSize(img.getSize());
                vo.setCreated(img.getCreated());
                vo.setContainers(img.getContainers());
                if (img.getLabels() != null) {
                    vo.setLabels(new LinkedHashMap<>(img.getLabels()));
                }
                result.add(vo);
            }
            result.sort((a, b) -> Long.compare(nvl(b.getCreated()), nvl(a.getCreated())));
            return result;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    public HostInfoVo info(Host host) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            HostInfoVo vo = new HostInfoVo();
            vo.setName(host == null ? null : host.getName());
            vo.setConnectionType(host == null ? null : host.getConnectionType());
            vo.setEndpoint(host == null ? null : host.getDockerHost());

            Version version = client.versionCmd().exec();
            vo.setDockerVersion(version.getVersion());
            vo.setApiVersion(version.getApiVersion());
            vo.setMinApiVersion(version.getMinAPIVersion());
            vo.setArch(version.getArch());
            vo.setKernelVersion(version.getKernelVersion());

            Info info = client.infoCmd().exec();
            vo.setOs(info.getOperatingSystem());
            vo.setArch(info.getArchitecture() != null ? info.getArchitecture() : vo.getArch());
            vo.setServerName(info.getName());
            vo.setLoggingDriver(info.getLoggingDriver());
            vo.setCgroupDriver(info.getCGroupDriver());
            vo.setMemory(info.getMemTotal());
            vo.setCpus(info.getNCPU());
            vo.setContainersRunning(info.getContainersRunning());
            vo.setContainersStopped(info.getContainersStopped());
            vo.setContainersTotal(info.getContainers());
            vo.setImages(info.getImages());
            return vo;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static String shortImageId(String id) {
        if (id == null) {
            return null;
        }
        String s = id.startsWith("sha256:") ? id.substring(7) : id;
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
