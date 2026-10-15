package io.github.jiangood.docker.admin.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PruneResponse;
import com.github.dockerjava.api.model.PruneType;
import com.github.dockerjava.api.model.Version;
import io.github.jiangood.docker.admin.dto.HostImageVo;
import io.github.jiangood.docker.admin.dto.HostInfoVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * 主机级 Docker 查询：镜像列表、引擎信息。
 */
@Slf4j
@Service
public class HostDockerService {

    /**
     * 「测试连接」的兜底超时。正常情况下 SSH 预检已经给出明确结论，
     * 这里只兜住传输层的异常情况（见 {@link DockerClientManager#callWithTimeout}）。
     */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15);

    @Resource
    private DockerClientManager dockerClientManager;

    /**
     * 测试主机 docker 连通性，返回远端 docker 版本号。
     */
    public String dockerVersion(Host host) {
        dockerClientManager.checkSshDocker(host);
        return dockerClientManager.callWithTimeout(host, TEST_TIMEOUT, client -> client.versionCmd().exec().getVersion());
    }

    public List<HostImageVo> listImages(Host host) {
        return withClient(host, client -> {
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
        });
    }

    /**
     * 删除镜像。入参为 {@code repo:tag} 时只删除该标签，为镜像 ID 时删除整个镜像。
     * <p>
     * 不使用 force：镜像被容器占用时由 Docker 返回 409，交由上层转换为可读提示。
     */
    public void removeImage(Host host, String image) {
        withClient(host, client -> {
            client.removeImageCmd(image).withNoPrune(false).exec();
            return null;
        });
    }

    /**
     * 清理悬空（无标签）镜像，返回回收的字节数。
     */
    public long pruneImages(Host host) {
        return withClient(host, client -> {
            PruneResponse response = client.pruneCmd(PruneType.IMAGES)
                    .withDangling(true)
                    .exec();
            Long reclaimed = response.getSpaceReclaimed();
            return reclaimed == null ? 0L : reclaimed;
        });
    }

    public HostInfoVo info(Host host) {
        return withClient(host, client -> info(client, host));
    }

    /**
     * 用已建立的客户端读取引擎信息，<b>不负责关闭客户端</b>。
     * <p>
     * 供需要自行控制超时与客户端生命周期的调用方使用（如首页汇总的
     * {@link io.github.jiangood.docker.sdk.engine.DockerClientManager#callWithTimeout}）。
     */
    public HostInfoVo info(DockerClient client, Host host) {
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
    }

    /**
     * 取客户端执行一次 docker 调用，调用方无需关心关闭。
     * <p>
     * 传输层失败（主机不可达、SSH 不通等）docker-java 会抛普通 {@link RuntimeException}，
     * 这里统一转成可读提示「连接容器引擎失败」；docker-java 自身的 {@link DockerException}
     * （如 404/409）原样抛出，由上层或全局处理器给出更具体的说明。
     */
    private <T> T withClient(Host host, Function<DockerClient, T> action) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            return action.apply(client);
        } catch (DockerException e) {
            throw e;
        } catch (Exception e) {
            log.warn("连接容器引擎失败：{}", e.toString());
            throw new BusinessException("连接容器引擎失败：" + rootMessage(e));
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 取最内层原因的消息，避免 docker-java 包装后带上冗长的类名前缀。
     */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
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
