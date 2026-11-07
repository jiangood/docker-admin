package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.RestartPolicy;
import io.github.jiangood.docker.admin.dto.HostAgentVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主机详情页「文件」与「Docker CLI」共用的辅助容器。
 * <p>
 * 三种连接方式（本机 / TCP / SSH）统一通过 Docker API 在该主机上运行一个 Linux 辅助容器：
 * <ul>
 *     <li>把当前选择的宿主机目录挂到容器内 {@code /host}，文件管理只操作这一块；
 *         目录在「文件」面板按需切换，切换时用新目录重建容器；</li>
 *     <li>同一个目录再挂到它自己的绝对路径，CLI 的工作目录也设在这里，
 *         这样 {@code docker compose} 的相对 bind 路径与宿主机一致；</li>
 *     <li>把宿主机 docker.sock 挂进容器，CLI Tab 里的 {@code docker} 命令直接操作宿主机 daemon。</li>
 * </ul>
 * 容器常驻、显式部署，删除后宿主机上的数据不受影响。
 */
@Slf4j
@Service
public class HostAgentService {

    public static final String CONTAINER_NAME = "docker-admin-agent";

    public static final String LABEL_AGENT = "io.github.jiangood.docker-admin.agent";
    public static final String LABEL_ROOT = LABEL_AGENT + ".root";
    public static final String LABEL_IMAGE = LABEL_AGENT + ".image";
    public static final String LABEL_SOCKET = LABEL_AGENT + ".socket";

    private static final String AGENT_MOUNT = HostRootPolicy.AGENT_MOUNT;
    private static final String SOCKET_TARGET = "/var/run/docker.sock";

    private static final String KEEP_ALIVE = "while true; do sleep 3600; done";

    @Resource
    private DockerClientManager dockerClientManager;

    @Resource
    private HostService hostService;

    @Resource
    private ContainerService containerService;

    @Resource
    private SystemConfigService systemConfigService;

    @Value("${docker.agent.socket-path:/var/run/docker.sock}")
    private String socketPath;

    /**
     * 辅助容器的时区。留空使用镜像默认（UTC），可配置为 Asia/Shanghai 等，
     * 避免文件管理器里的修改时间与宿主机差几个小时。
     */
    @Value("${docker.agent.tz:}")
    private String timezone;

    /**
     * 同一主机的部署串行化，避免两个人同时点「部署」重复创建同名容器。
     */
    private final Map<String, Object> deployLocks = new ConcurrentHashMap<>();

    /**
     * 运行中的辅助容器：文件接口与 CLI 都用它定位容器，并使用实际挂载的目录。
     */
    public record AgentContext(String containerId, String root) {
    }

    // ------------------------------------------------------------------ 状态 / 部署 / 删除

    public HostAgentVo status(Host host) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            return status(client, host);
        } catch (BusinessException | DockerException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取主机辅助容器状态失败: host={}", host.getId(), e);
            throw new BusinessException("连接容器引擎失败：" + rootMessage(e));
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 部署辅助容器，或按新目录 / 镜像重建。
     * <p>
     * 宿主机目录不再在部署时填写：没传目录时沿用主机上保存的目录（首次为默认 {@code /home}），
     * 之后在「文件」面板里按需切换（切换同样走这里，配置不同会自动重建容器）。
     *
     * @param requestedRoot 要挂载的宿主机目录；为空时使用 Host.fileRoot 或默认 /home
     * @param requestedTag  该主机选择的 agent 镜像 tag；为空时使用 Host.agentImageTag 或 latest
     * @param recreate      为 true 时，即使目录与镜像都没变也删除旧容器重建
     */
    public HostAgentVo deploy(Host host, String requestedRoot, String requestedTag, boolean recreate) {
        String root = HostRootPolicy.normalizeRoot(
                StrUtil.blankToDefault(StrUtil.trimToNull(requestedRoot), host.resolveFileRoot()));
        HostRootPolicy.assertValidRoot(root);
        String tag = AgentImageUtils.normalizeTag(
                StrUtil.blankToDefault(StrUtil.trimToNull(requestedTag), host.resolveAgentImageTag()));
        String image = AgentImageUtils.resolveImage(systemConfigService.getAgentRepository(), tag);

        synchronized (deployLocks.computeIfAbsent(host.getId(), key -> new Object())) {
            DockerClient client = null;
            try {
                client = dockerClientManager.getClient(host);

                InspectContainerResponse existing = inspect(client);
                if (existing != null) {
                    Map<String, String> labels = labelsOf(existing);
                    if (!isOurAgent(labels)) {
                        throw sameNameConflict();
                    }
                    boolean same = root.equals(labels.get(LABEL_ROOT))
                            && image.equals(labels.get(LABEL_IMAGE))
                            && socketPath.equals(labels.get(LABEL_SOCKET));
                    if (same && !recreate) {
                        if (!isRunning(existing)) {
                            start(client);
                            waitRunning(client);
                        }
                        persist(host, root, tag);
                        return status(client, host);
                    }
                }

                // 先把镜像准备好，再动旧容器：拉取失败时旧 agent 仍然可用
                // latest 在「重建」时强制拉取；固定版本本地已有则直接复用
                ensureImage(client, image, recreate && AgentImageUtils.DEFAULT_TAG.equals(tag));

                if (existing != null) {
                    // 目录/镜像配置变了，或用户点了「重建」：旧容器直接重建
                    removeAgent(client);
                }

                createAgent(client, root, image);
                start(client);
                waitRunning(client);
                assertMountDirectory(host, client, root);

                // 容器成功起来后才落库：切换目录失败时保留旧目录，
                // 「重新部署」还能按原目录恢复，不会把主机卡在一个无效目录上
                persist(host, root, tag);
                return status(client, host);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("部署主机辅助容器失败: host={} root={}", host.getId(), root, e);
                throw new BusinessException("部署辅助容器失败：" + rootMessage(e)
                        + "。请确认目录存在、Docker Desktop 已共享该磁盘（Docker VMM 需在 Settings > Resources > File sharing 添加目录），"
                        + "且填写的是 daemon 可见的 Linux 路径");
            } finally {
                IOUtils.closeQuietly(client);
            }
        }
    }

    /**
     * 记录本机使用的目录与镜像 tag。失败时保留旧值，避免部署失败后无法回退。
     */
    private void persist(Host host, String root, String tag) {
        hostService.updateFileRoot(host.getId(), root);
        hostService.updateAgentImageTag(host.getId(), tag);
        host.setFileRoot(root);
        host.setAgentImageTag(tag);
    }

    public void remove(Host host) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            InspectContainerResponse existing = inspect(client);
            if (existing != null && !isOurAgent(labelsOf(existing))) {
                throw sameNameConflict();
            }
            removeAgent(client);
        } catch (BusinessException | DockerException e) {
            throw e;
        } catch (Exception e) {
            log.warn("删除主机辅助容器失败: host={}", host.getId(), e);
            throw new BusinessException("连接容器引擎失败：" + rootMessage(e));
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 文件/CLI 接口入口：确保辅助容器存在并运行，返回实际挂载目录。
     */
    public AgentContext requireRunning(Host host) {
        DockerClient client = null;
        try {
            client = dockerClientManager.getClient(host);
            InspectContainerResponse response = inspect(client);
            if (response == null) {
                throw new BusinessException("辅助容器未部署，请先在主机详情页部署辅助容器");
            }
            Map<String, String> labels = labelsOf(response);
            if (!isOurAgent(labels)) {
                throw sameNameConflict();
            }
            if (!isRunning(response)) {
                start(client);
                waitRunning(client);
            }
            String root = StrUtil.blankToDefault(labels.get(LABEL_ROOT), host.resolveFileRoot());
            return new AgentContext(response.getId(), root);
        } catch (BusinessException | DockerException e) {
            throw e;
        } catch (Exception e) {
            log.warn("连接主机辅助容器失败: host={}", host.getId(), e);
            throw new BusinessException("连接容器引擎失败：" + rootMessage(e));
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 容器生命周期

    private HostAgentVo status(DockerClient client, Host host) {
        String repository = systemConfigService.getAgentRepository();
        String tag = host.resolveAgentImageTag();
        String image = AgentImageUtils.resolveImage(repository, tag);

        HostAgentVo vo = new HostAgentVo();
        vo.setRepository(repository);
        vo.setTag(tag);
        vo.setImage(image);
        vo.setDesiredRoot(host.resolveFileRoot());
        vo.setSocketPath(socketPath);

        InspectContainerResponse response = inspect(client);
        if (response == null) {
            return vo;
        }
        Map<String, String> labels = labelsOf(response);
        if (!isOurAgent(labels)) {
            vo.setMessage(sameNameConflict().getMessage());
            return vo;
        }

        vo.setDeployed(true);
        vo.setContainerId(response.getId());
        vo.setContainerName(StrUtil.removePrefix(StrUtil.nullToEmpty(response.getName()), "/"));
        vo.setRunning(isRunning(response));
        vo.setMountedRoot(labels.get(LABEL_ROOT));
        vo.setMountedImage(labels.get(LABEL_IMAGE));
        vo.setNeedsRebuild(!Objects.equals(vo.getDesiredRoot(), vo.getMountedRoot())
                || !Objects.equals(image, labels.get(LABEL_IMAGE))
                || !Objects.equals(socketPath, labels.get(LABEL_SOCKET)));
        return vo;
    }

    private InspectContainerResponse inspect(DockerClient client) {
        try {
            return client.inspectContainerCmd(CONTAINER_NAME).exec();
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * 确保镜像可用：不存在时拉取；{@code forcePull} 为 true（重建 latest）时先尝试拉取；
     * 拉取失败但本地已有该镜像时继续使用本地镜像，方便无外网/已预加载的主机。
     */
    private void ensureImage(DockerClient client, String image, boolean forcePull) throws Exception {
        boolean exists = imageExists(client, image);
        if (exists && !forcePull) {
            return;
        }

        try {
            log.info("拉取辅助镜像 {}{}", image, exists ? "（尝试更新）" : "");
            ResultCallback.Adapter<PullResponseItem> callback = new ResultCallback.Adapter<>() {
            };
            client.pullImageCmd(repoOf(image)).withTag(tagOf(image)).exec(callback).awaitCompletion();
        } catch (Exception e) {
            if (exists) {
                log.warn("辅助镜像 {} 拉取失败，使用本地已有镜像: {}", image, rootMessage(e));
                return;
            }
            throw new BusinessException("辅助镜像 " + image + " 不存在且拉取失败：" + rootMessage(e)
                    + "。请在目标主机预先拉取或 docker load 该镜像，或检查镜像仓库网络/登录状态");
        }
    }

    private boolean imageExists(DockerClient client, String image) {
        try {
            client.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private void createAgent(DockerClient client, String root, String image) {
        HostConfig hostConfig = new HostConfig()
                .withMounts(buildMounts(root))
                // SELinux 主机必需；不要用 :z，避免递归修改宿主机文件系统 label
                .withSecurityOpts(List.of("label=disable"))
                .withReadonlyRootfs(true)
                // compose build 等操作可能需要临时空间；rootfs 只读，/tmp 是唯一可写区域
                .withTmpFs(Map.of("/tmp", "rw,exec,nosuid,size=512m"))
                .withCapDrop(Capability.MKNOD, Capability.NET_RAW, Capability.AUDIT_WRITE)
                .withAutoRemove(false)
                .withRestartPolicy(RestartPolicy.noRestart());

        List<String> env = new ArrayList<>();
        env.add("DOCKER_HOST=unix://" + SOCKET_TARGET);
        env.add("DOCKER_CONFIG=/tmp/.docker");
        env.add("HOME=/tmp");
        if (StrUtil.isNotBlank(timezone)) {
            env.add("TZ=" + timezone.trim());
        }

        client.createContainerCmd(image)
                .withName(CONTAINER_NAME)
                .withLabels(Map.of(
                        LABEL_AGENT, "true",
                        LABEL_ROOT, root,
                        LABEL_IMAGE, image,
                        LABEL_SOCKET, socketPath))
                .withEnv(env)
                // 与宿主机保持相同绝对路径，docker compose 的相对 bind 路径才会正确
                .withWorkingDir(root)
                .withEntrypoint("/bin/sh", "-c")
                .withCmd(KEEP_ALIVE)
                .withHostConfig(hostConfig)
                .withTty(false)
                .exec();
    }

    /**
     * 同一个宿主机目录挂两次：
     * <ul>
     *     <li>{@code /host}：文件管理使用；</li>
     *     <li>原始绝对路径：CLI 的工作目录，保证 {@code docker compose} 的相对 bind 路径与宿主机一致。</li>
     * </ul>
     * 不使用 rslave：Docker Desktop 和多数 Linux 主机的 /home 都是 / 上的普通目录，
     * 不是 shared/slave 挂载，指定 rslave 会直接报
     * {@code path ... is mounted on / but it is not a shared or slave mount}。
     * 默认传播即可；宿主机后续新挂载的磁盘需要重建辅助容器才能看到。
     */
    private List<Mount> buildMounts(String root) {
        List<Mount> mounts = new ArrayList<>();
        mounts.add(new Mount()
                .withType(MountType.BIND)
                .withSource(root)
                .withTarget(AGENT_MOUNT)
                .withReadOnly(false));
        mounts.add(new Mount()
                .withType(MountType.BIND)
                .withSource(root)
                .withTarget(root)
                .withReadOnly(false));
        mounts.add(new Mount()
                .withType(MountType.BIND)
                .withSource(socketPath)
                .withTarget(SOCKET_TARGET)
                .withReadOnly(false));
        return mounts;
    }

    private void start(DockerClient client) {
        try {
            client.startContainerCmd(CONTAINER_NAME).exec();
        } catch (NotModifiedException ignored) {
            // 已经在运行
        }
    }

    /**
     * docker start 返回后容器状态通常已经 running；这里再短暂等待一下，
     * 避免紧接着 exec 时偶发「container is not running」。
     */
    private void waitRunning(DockerClient client) {
        for (int i = 0; i < 50; i++) {
            InspectContainerResponse response = inspect(client);
            if (response != null && isRunning(response)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 挂载源必须是真实目录：填了文件、或者软链接最终指向不可访问的位置时，
     * 容器虽然能起来，但 /host 不是目录，文件 Tab 会一直报错。
     */
    private void assertMountDirectory(Host host, DockerClient client, String root) {
        InspectContainerResponse created = inspect(client);
        if (created == null) {
            return;
        }
        ContainerService.CommandResult result;
        try {
            result = containerService.exec(host, created.getId(),
                    List.of("sh", "-c", "test -d \"$1\"", "sh", AGENT_MOUNT));
        } catch (RuntimeException e) {
            removeAgent(client);
            throw new BusinessException("辅助容器启动后无法访问挂载目录：" + root, e);
        }
        if (result.exitCode() != 0) {
            removeAgent(client);
            throw new BusinessException("挂载目录不是有效目录：" + root
                    + "。请确认它存在于 Docker daemon 所在文件系统，且不要填写文件或指向系统目录的软链接");
        }
    }

    private void removeAgent(DockerClient client) {
        try {
            client.stopContainerCmd(CONTAINER_NAME).exec();
        } catch (DockerException e) {
            // 未运行、已暂停或不存在：继续尝试强制删除
            log.debug("停止辅助容器失败，继续删除: {}", e.getMessage());
        }
        try {
            client.removeContainerCmd(CONTAINER_NAME).withForce(true).withRemoveVolumes(false).exec();
        } catch (NotFoundException ignored) {
            // 已经不存在
        }
    }

    // ------------------------------------------------------------------ 工具

    private static boolean isRunning(InspectContainerResponse response) {
        return response.getState() != null && Boolean.TRUE.equals(response.getState().getRunning());
    }

    private static Map<String, String> labelsOf(InspectContainerResponse response) {
        if (response.getConfig() == null || response.getConfig().getLabels() == null) {
            return Map.of();
        }
        return response.getConfig().getLabels();
    }

    private static boolean isOurAgent(Map<String, String> labels) {
        return "true".equals(labels.get(LABEL_AGENT));
    }

    private static BusinessException sameNameConflict() {
        return new BusinessException("已存在同名容器 " + CONTAINER_NAME
                + "，但不是本平台创建的，请先改名或删除后再部署");
    }

    private static String repoOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(0, lastColon) : image;
    }

    private static String tagOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(lastColon + 1) : "latest";
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return StrUtil.blankToDefault(cause.getMessage(), cause.getClass().getSimpleName());
    }
}
