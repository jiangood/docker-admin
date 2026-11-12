package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectContainerResponse.Mount;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.jiangood.docker.admin.config.DockerSettings;
import io.github.jiangood.docker.admin.dto.ContainerDetailVo;
import io.github.jiangood.docker.admin.dto.ContainerFileVo;
import io.github.jiangood.docker.admin.dto.ContainerSummaryVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 通用容器操作：列表、inspect、文件、操作。
 * <p>
 * 与 {@link AppService} 不同，这里不依赖应用标签，直接以 hostId + containerId 定位容器，
 * 供「主机详情」与「应用详情」的通用容器组件使用。
 */
@Slf4j
@Service
public class ContainerService {

    /** 文本预览最大字节数。 */
    private static final int PREVIEW_MAX_BYTES = 262144;

    /**
     * 禁止删除的系统关键路径：删掉这些目录/文件容器基本就无法运行了。
     * <ul>
     *   <li>目录按「路径本身」精确保护，其下的文件仍可按需删除（如 /etc/nginx/conf.d/xx.conf）；</li>
     *   <li>/proc、/sys、/dev 是内核伪文件系统，本来就没有用户数据，整个前缀保护。</li>
     * </ul>
     * 前端 {@code web/src/components/container/ContainerFiles.jsx} 有同名置灰列表，两边需同步维护。
     */
    private static final List<String> PROTECTED_DELETE_PATHS = List.of(
            "/bin", "/sbin", "/usr", "/lib", "/lib64", "/lib32", "/libx32",
            "/boot", "/etc", "/root", "/home", "/run", "/var/run",
            "/proc", "/sys", "/dev",
            "/etc/passwd", "/etc/shadow", "/etc/group", "/etc/gshadow",
            "/etc/hosts", "/etc/hostname", "/etc/resolv.conf", "/etc/nsswitch.conf", "/etc/profile");
    private static final List<String> PROTECTED_DELETE_PREFIXES = List.of("/proc/", "/sys/", "/dev/");

    /**
     * 容器启停删的兜底超时：docker-java 的 responseTimeout 是 1 小时（拉镜像需要），
     * 而这类命令本该秒回，宿主机存储卡住（如 bind 源目录在无响应的 NFS 上）时必须尽快失败。
     * <p>
     * 停止 / 重启不用这个值：它们要覆盖「系统参数 - 容器运行」里的宽限期，
     * 由 {@link DockerSettings#budget()} 计算（宽限期 + 30 秒响应预算）。
     */
    private static final Duration CONTAINER_ACTION_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 删除超时后回查容器是否还在的预算。
     * <p>
     * 删除请求超时只代表平台没等到响应，dockerd 往往仍在后台继续删；回查一次能把
     * 「其实已经删掉」的情况按成功处理（否则用户重试还会收到 404）。
     * daemon 真卡住时也不能让用户再等两分钟，所以这个预算给得很小。
     */
    private static final Duration CONTAINER_VERIFY_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 清理构建临时容器的兜底超时。容器可能很多，每台都要 inspect 一次再逐个删除，
     * 比单容器操作慢；但仍不应让前端一直停在「清理中」。
     */
    private static final Duration BUILD_LEFTOVER_TIMEOUT = Duration.ofMinutes(3);

    /** 平台应用容器的标签，见 {@link DockerClientManager#getAppLabelFilter(String)}。 */
    private static final String APP_LABEL = "app.name";

    /** docker compose 创建的容器带此标签，清理时一律跳过。 */
    private static final String COMPOSE_LABEL = "com.docker.compose.project";

    /** 无 ls 时从 archive 接口推断出的修改时间格式，与 {@code ls --time-style=long-iso} 对齐。 */
    private static final DateTimeFormatter MTIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern MODE_PATTERN = Pattern.compile("^[bcdlps-][rwxSsTt-]{9}[.+]?$");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Resource
    private DockerClientManager dockerClientManager;

    @Resource
    private DockerSettings dockerSettings;

    // ------------------------------------------------------------------ 列表

    public List<ContainerSummaryVo> list(Host host, boolean showAll) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<Container> list = client.listContainersCmd().withShowAll(showAll).exec();
            List<ContainerSummaryVo> result = new ArrayList<>(list.size());
            for (Container c : list) {
                result.add(toSummary(c));
            }
            return result;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static ContainerSummaryVo toSummary(Container c) {
        ContainerSummaryVo vo = new ContainerSummaryVo();
        vo.setId(c.getId());
        vo.setIdShort(shortId(c.getId()));
        vo.setName(firstName(c.getNames()));
        vo.setImage(c.getImage());
        vo.setImageId(c.getImageId());
        vo.setState(c.getState());
        vo.setStatus(c.getStatus());
        vo.setCreated(c.getCreated());
        vo.setCommand(c.getCommand());
        if (c.getLabels() != null) {
            vo.setLabels(new LinkedHashMap<>(c.getLabels()));
        }
        if (c.getPorts() != null) {
            for (ContainerPort p : c.getPorts()) {
                ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                pv.setPrivatePort(p.getPrivatePort());
                pv.setPublicPort(p.getPublicPort());
                pv.setProtocol(p.getType());
                pv.setHostIp(p.getIp());
                vo.getPorts().add(pv);
            }
        }
        return vo;
    }

    // ------------------------------------------------------------------ inspect

    public ContainerDetailVo inspect(Host host, String containerId) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            InspectContainerResponse r = client.inspectContainerCmd(containerId).exec();
            return toDetail(r);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private static ContainerDetailVo toDetail(InspectContainerResponse r) {
        ContainerDetailVo vo = new ContainerDetailVo();
        vo.setId(r.getId());
        vo.setIdShort(shortId(r.getId()));
        vo.setName(r.getName() != null && r.getName().startsWith("/") ? r.getName().substring(1) : r.getName());
        vo.setImageId(r.getImageId());
        vo.setCreated(r.getCreated());
        vo.setRestartCount(r.getRestartCount());

        ContainerConfig cfg = r.getConfig();
        if (cfg != null) {
            vo.setImage(cfg.getImage());
            vo.setCmd(cfg.getCmd());
            vo.setEntrypoint(cfg.getEntrypoint());
            vo.setWorkingDir(cfg.getWorkingDir());
            vo.setUser(cfg.getUser());
            vo.setHostname(cfg.getHostName());
            if (cfg.getEnv() != null) {
                vo.setEnv(new ArrayList<>(Arrays.asList(cfg.getEnv())));
            }
            if (cfg.getLabels() != null) {
                vo.setLabels(new LinkedHashMap<>(cfg.getLabels()));
            }
        }

        ContainerState st = r.getState();
        if (st != null) {
            vo.setState(st.getStatus());
            vo.setStatus(st.getStatus());
            vo.setRunning(st.getRunning());
            vo.setPaused(st.getPaused());
            vo.setRestarting(st.getRestarting());
            vo.setDead(st.getDead());
            vo.setExitCode(st.getExitCode());
            vo.setError(st.getError());
            vo.setStartedAt(st.getStartedAt());
            vo.setFinishedAt(st.getFinishedAt());
        }

        HostConfig hc = r.getHostConfig();
        if (hc != null) {
            vo.setPrivileged(hc.getPrivileged());
            vo.setNetworkMode(hc.getNetworkMode());
            RestartPolicy rp = hc.getRestartPolicy();
            if (rp != null) {
                String name = rp.getName();
                if (name != null && rp.getMaximumRetryCount() != null && rp.getMaximumRetryCount() > 0) {
                    name = name + ":" + rp.getMaximumRetryCount();
                }
                vo.setRestartPolicy(name);
            }
            LogConfig lc = hc.getLogConfig();
            if (lc != null && lc.getType() != null) {
                vo.setLogDriver(lc.getType().name().toLowerCase().replace('_', '-'));
            }
        }

        if (r.getMounts() != null) {
            for (Mount m : r.getMounts()) {
                ContainerDetailVo.MountVo mv = new ContainerDetailVo.MountVo();
                // 命名卷有 name，绑定挂载没有；用于区分 volume / bind
                mv.setType(StrUtil.isNotBlank(m.getName()) ? "volume" : "bind");
                mv.setName(m.getName());
                mv.setSource(m.getSource());
                mv.setDestination(m.getDestination() == null ? null : m.getDestination().getPath());
                mv.setMode(m.getMode());
                mv.setReadOnly(m.getRW() == null ? null : !m.getRW());
                vo.getMounts().add(mv);
            }
        }

        NetworkSettings ns = r.getNetworkSettings();
        if (ns != null) {
            Ports ports = ns.getPorts();
            if (ports != null && ports.getBindings() != null) {
                for (Map.Entry<ExposedPort, Ports.Binding[]> e : ports.getBindings().entrySet()) {
                    ExposedPort ep = e.getKey();
                    Ports.Binding[] bindings = e.getValue();
                    if (bindings == null || bindings.length == 0) {
                        ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                        pv.setPrivatePort(ep.getPort());
                        pv.setProtocol(ep.getProtocol().toString());
                        vo.getPorts().add(pv);
                        continue;
                    }
                    for (Ports.Binding b : bindings) {
                        ContainerDetailVo.PortVo pv = new ContainerDetailVo.PortVo();
                        pv.setPrivatePort(ep.getPort());
                        pv.setProtocol(ep.getProtocol().toString());
                        pv.setHostIp(b.getHostIp());
                        pv.setPublicPort(parsePort(b.getHostPortSpec()));
                        vo.getPorts().add(pv);
                    }
                }
            }
            if (ns.getNetworks() != null) {
                for (Map.Entry<String, ContainerNetwork> e : ns.getNetworks().entrySet()) {
                    ContainerNetwork n = e.getValue();
                    ContainerDetailVo.NetworkVo nv = new ContainerDetailVo.NetworkVo();
                    nv.setName(e.getKey());
                    nv.setIpAddress(n.getIpAddress());
                    nv.setGateway(n.getGateway());
                    nv.setMacAddress(n.getMacAddress());
                    vo.getNetworks().add(nv);
                }
            }
        }
        return vo;
    }

    private static Integer parsePort(String spec) {
        if (StrUtil.isBlank(spec)) {
            return null;
        }
        try {
            return Integer.parseInt(spec.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 操作

    public void start(Host host, String containerId) {
        withClient(host, "启动容器 " + containerId, client -> client.startContainerCmd(containerId).exec());
    }

    public void stop(Host host, String containerId) {
        int stopGraceSeconds = dockerSettings.graceSeconds();
        Duration stopBudget = dockerSettings.budget();
        try {
            withClient(host, "停止容器 " + containerId, stopBudget,
                    client -> client.stopContainerCmd(containerId).withTimeout(stopGraceSeconds).exec());
        } catch (IllegalStateException e) {
            // docker stop 是「SIGTERM → 等宽限期 → SIGKILL」，宽限期在「系统参数 - 容器运行」里配置（默认 60 秒）；
            // 超时多半是容器里有进程卡在不可中断（D）状态，回查一次确认实际状态再报错
            Boolean stopped = isStopped(host, containerId);
            if (Boolean.TRUE.equals(stopped)) {
                log.warn("停止容器 {} 未在 {} 秒内返回，但回查确认容器已停止", containerId, stopBudget.toSeconds());
                return;
            }
            if (Boolean.FALSE.equals(stopped)) {
                throw new IllegalStateException(e.getMessage()
                        + "。回查显示容器仍在运行：容器内可能有进程卡在不可中断（D）状态"
                        + "（例如挂载的网络存储失联），SIGKILL 也要等内核返回，请登录宿主机用 ps / dmesg / mount 检查", e);
            }
            throw new IllegalStateException(e.getMessage()
                    + "。无法确认容器状态，请登录宿主机执行 docker ps -a 检查", e);
        }
    }

    public void restart(Host host, String containerId) {
        // 重启同样先 SIGTERM 再等宽限期，宽限期与停止保持一致
        int stopGraceSeconds = dockerSettings.graceSeconds();
        withClient(host, "重启容器 " + containerId, dockerSettings.budget(),
                client -> client.restartContainerCmd(containerId).withTimeout(stopGraceSeconds).exec());
    }

    public void remove(Host host, String containerId, boolean force) {
        try {
            withClient(host, "删除容器 " + containerId, client -> client.removeContainerCmd(containerId)
                    .withForce(force)
                    .withRemoveVolumes(false)
                    .exec());
        } catch (NotFoundException e) {
            // 容器本来就不存在（并发删除，或上一次超时其实已完成）：删除是幂等的，按成功处理
            log.info("容器 {} 已不存在，视为删除成功", containerId);
        } catch (ConflictException e) {
            // docker 不允许删除 running / paused 的容器（409），换成用户能直接照做的提示
            throw new BusinessException("容器正在运行，请先停止，或勾选「强制删除」");
        } catch (IllegalStateException e) {
            // 超时只说明客户端没等到响应，dockerd 可能还在后台执行：回查一次再决定怎么说
            Boolean gone = isGone(host, containerId);
            if (Boolean.TRUE.equals(gone)) {
                log.warn("删除容器 {} 未在 {} 秒内返回，但回查确认已删除", containerId,
                        CONTAINER_ACTION_TIMEOUT.toSeconds());
                return;
            }
            if (Boolean.FALSE.equals(gone)) {
                throw new IllegalStateException(e.getMessage()
                        + "。回查确认容器仍然存在，可登录宿主机执行 docker rm -f " + containerId + " 处理", e);
            }
            throw new IllegalStateException(e.getMessage()
                    + "。无法确认容器是否已删除，请登录宿主机执行 docker ps -a 检查", e);
        }
    }

    /**
     * 清理构建失败 / 中断后残留的临时容器，返回实际删除的数量。
     * <p>
     * 「经典」构建器（{@code POST /build}）每执行一步都会创建临时容器，正常情况下由
     * {@code rm=1} 在构建结束时删除；构建被取消、平台重启或宿主机异常时可能残留，
     * 表现为一串已停止、名字随机的容器。它们没有专用标签，只能按
     * {@link #isBuildLeftover(InspectContainerResponse)} 里的特征识别，
     * 所以界面上仍要求用户二次确认。
     */
    public int pruneBuildLeftovers(Host host) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            return dockerClientManager.callWithTimeout(BUILD_LEFTOVER_TIMEOUT, "清理构建临时容器",
                    () -> removeBuildLeftovers(client));
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage()
                    + "。宿主机可能卡在存储挂载或 docker 守护进程无响应，请登录宿主机检查", e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private int removeBuildLeftovers(DockerClient client) {
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        int removed = 0;
        for (Container c : containers) {
            // 先用列表里已有的状态 / 标签粗筛，避免对每个停止容器都发一次 inspect
            if (!mayBeBuildLeftover(c)) {
                continue;
            }
            try {
                if (!isBuildLeftover(client.inspectContainerCmd(c.getId()).exec())) {
                    continue;
                }
                client.removeContainerCmd(c.getId())
                        .withForce(true)
                        // 构建步骤里 VOLUME 产生的匿名卷一并回收；命名卷不受影响
                        .withRemoveVolumes(true)
                        .exec();
                removed++;
                log.info("已清理构建临时容器 {} ({})", shortId(c.getId()), firstName(c.getNames()));
            } catch (NotFoundException e) {
                // 并发删除或上一次请求其实已完成：删除是幂等的
                log.info("构建临时容器 {} 已不存在", shortId(c.getId()));
            } catch (Exception e) {
                // 一个容器删不掉不应该让整轮清理失败，记录后继续
                log.warn("清理构建临时容器 {} 失败: {}", shortId(c.getId()), e.getMessage());
            }
        }
        return removed;
    }

    /** 不需要 inspect 的粗筛：只保留已停止、且不带平台 / Agent / Compose 标签的容器。 */
    private static boolean mayBeBuildLeftover(Container c) {
        String state = c.getState();
        if (!"created".equals(state) && !"exited".equals(state)) {
            return false;
        }
        Map<String, String> labels = c.getLabels();
        return labels == null
                || (!labels.containsKey(APP_LABEL)
                && !labels.containsKey(HostAgentService.LABEL_AGENT)
                && !labels.containsKey(COMPOSE_LABEL));
    }

    /**
     * 构建临时容器的特征：已停止、日志驱动为 none、不是平台应用 / Agent / Compose 容器。
     * <p>
     * 构建器创建中间容器时固定写入 {@code LogConfig=none}（所以构建过程中的容器看不到日志），
     * 这是与普通容器最可靠的区分点；应用容器、Agent 容器和 compose 容器另外用标签排除。
     */
    private static boolean isBuildLeftover(InspectContainerResponse r) {
        ContainerState state = r.getState();
        if (state == null || Boolean.TRUE.equals(state.getRunning())) {
            return false;
        }
        String status = state.getStatus();
        if (!"created".equals(status) && !"exited".equals(status)) {
            return false;
        }
        ContainerConfig cfg = r.getConfig();
        if (cfg != null && cfg.getLabels() != null) {
            Map<String, String> labels = cfg.getLabels();
            if (labels.containsKey(APP_LABEL)
                    || labels.containsKey(HostAgentService.LABEL_AGENT)
                    || labels.containsKey(COMPOSE_LABEL)) {
                return false;
            }
        }
        HostConfig hostConfig = r.getHostConfig();
        LogConfig logConfig = hostConfig == null ? null : hostConfig.getLogConfig();
        return logConfig != null && logConfig.getType() == LogConfig.LoggingType.NONE;
    }

    /**
     * 回查容器是否已不存在（删除超时后的判定用）。
     *
     * @return {@code true} 确认已不存在；{@code false} 确认仍存在；{@code null} 无法确认（daemon 同样没响应）
     */
    private Boolean isGone(Host host, String containerId) {
        try {
            inspectForVerify(host, containerId);
            return false;
        } catch (NotFoundException e) {
            return true;
        } catch (RuntimeException e) {
            log.debug("回查容器 {} 失败，无法确认删除结果: {}", containerId, e.getMessage());
            return null;
        }
    }

    /**
     * 回查容器是否已停止（停止超时后的判定用）。
     *
     * @return {@code true} 已停止或已不存在；{@code false} 仍在运行；{@code null} 无法确认
     */
    private Boolean isStopped(Host host, String containerId) {
        try {
            InspectContainerResponse r = inspectForVerify(host, containerId);
            ContainerState state = r.getState();
            return state == null || !Boolean.TRUE.equals(state.getRunning());
        } catch (NotFoundException e) {
            return true;
        } catch (RuntimeException e) {
            log.debug("回查容器 {} 失败，无法确认停止结果: {}", containerId, e.getMessage());
            return null;
        }
    }

    /**
     * 用很小的预算回查容器，避免 daemon 卡住时再次让用户干等。
     */
    private InspectContainerResponse inspectForVerify(Host host, String containerId) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            return dockerClientManager.callWithTimeout(CONTAINER_VERIFY_TIMEOUT, "回查容器 " + containerId,
                    () -> client.inspectContainerCmd(containerId).exec());
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private interface ClientAction {
        void run(DockerClient client);
    }

    /**
     * 执行一条容器操作，并兜底超时。
     * <p>
     * 超时值是 docker-java 全局 responseTimeout（1 小时，拉镜像需要）之外的收紧：
     * 宿主机存储卡住（如 bind 源目录在无响应的 NFS 上）时，这些命令不会返回，
     * 前端请求会一直挂着，因此这里限时失败并给出可操作的错误消息。
     * <p>
     * 带 {@code timeout} 的重载给停止 / 重启用，超时预算要覆盖容器自身的退出宽限期。
     */
    private void withClient(Host host, String action, ClientAction run) {
        withClient(host, action, CONTAINER_ACTION_TIMEOUT, run);
    }

    private void withClient(Host host, String action, Duration timeout, ClientAction run) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            dockerClientManager.callWithTimeout(timeout, action, () -> {
                run.run(client);
                return null;
            });
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage()
                    + "。宿主机可能卡在存储挂载或 docker 守护进程无响应，请登录宿主机检查", e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 文件

    /**
     * 列出容器内目录。
     * <p>
     * 优先用容器内的 {@code ls}（快，且不需要拉取整个目录树）；镜像里没有 {@code ls} 时
     * （distroless / scratch 等）回退到 Docker archive 接口，由 dockerd 直接读取文件系统。
     */
    public List<ContainerFileVo> listFiles(Host host, String containerId, String path) {
        String dir = StrUtil.blankToDefault(path, "/");
        DockerClient client = dockerClientManager.getClient(host);
        try {
            List<ContainerFileVo> byLs = listFilesByLs(client, containerId, dir);
            if (byLs != null) {
                return byLs;
            }
            return listFilesByArchive(client, containerId, dir);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 用容器内的 ls 列目录；容器没有 ls 或执行失败时返回 null，由 archive 兜底。 */
    private List<ContainerFileVo> listFilesByLs(DockerClient client, String containerId, String dir) {
        try {
            CommandResult result = execCapture(client, containerId, List.of("ls", "-la", "--time-style=long-iso", dir));
            if (result.exitCode != 0) {
                // 兼容 busybox 等不支持 --time-style 的环境，退回默认格式
                result = execCapture(client, containerId, List.of("ls", "-la", dir));
            }
            if (result.exitCode == 0) {
                return parseLs(dir, result.stdout);
            }
        } catch (RuntimeException e) {
            log.debug("容器 {} 执行 ls 失败，改用 archive 接口: {}", containerId, e.getMessage());
        }
        return null;
    }

    /**
     * 用 Docker archive 接口列目录（不依赖容器内的任何二进制）。
     * <p>
     * archive 返回的是以「请求路径所在目录名」为前缀的 tar，根目录则为绝对路径；
     * 这里只取第一层条目，更深的层级直接跳过。
     */
    private List<ContainerFileVo> listFilesByArchive(DockerClient client, String containerId, String dir) {
        String base = dir.endsWith("/") ? dir : dir + "/";
        String prefix;
        if ("/".equals(base)) {
            prefix = "";
        } else {
            String d = base.substring(0, base.length() - 1);
            prefix = d.substring(d.lastIndexOf('/') + 1) + "/";
        }

        List<ContainerFileVo> list = new ArrayList<>();
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, dir).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String rest = stripArchivePrefix(prefix, entry.getName());
                if (rest == null || rest.isEmpty() || rest.indexOf('/') >= 0) {
                    continue;
                }
                ContainerFileVo vo = new ContainerFileVo();
                vo.setName(rest);
                vo.setPath(base + rest);
                vo.setMode(modeString(entry));
                vo.setMtime(formatArchiveMtime(entry));
                if (entry.isDirectory()) {
                    vo.setType("dir");
                } else if (entry.isSymbolicLink()) {
                    vo.setType("link");
                    String target = entry.getLinkName();
                    vo.setLinkTarget(target);
                    // 与 ls -l 保持一致：链接的大小显示为目标路径长度
                    vo.setSize(target == null ? null : (long) target.length());
                } else {
                    vo.setType("file");
                    vo.setSize(entry.getSize());
                }
                list.add(vo);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法列出目录: " + dir, e);
        }

        list.sort(Comparator
                .comparing((ContainerFileVo f) -> !"dir".equals(f.getType()))
                .thenComparing(ContainerFileVo::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    /** 去掉 archive 条目名前缀，返回相对当前目录的路径；不属于该目录时返回 null。 */
    private static String stripArchivePrefix(String prefix, String entryName) {
        String rest;
        if (prefix.isEmpty()) {
            rest = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        } else if (entryName.startsWith(prefix)) {
            rest = entryName.substring(prefix.length());
        } else {
            return null;
        }
        // 目录条目以 / 结尾，去掉后再判断深度
        return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
    }

    /** 由 tar 条目的权限位还原 ls 风格的模式串（如 {@code -rw-r--r--}）。 */
    private static String modeString(TarArchiveEntry entry) {
        int mode = entry.getMode();
        char type = entry.isDirectory() ? 'd' : entry.isSymbolicLink() ? 'l' : '-';
        int[] bits = {0400, 0200, 0100, 040, 020, 010, 04, 02, 01};
        char[] chars = {'r', 'w', 'x'};
        StringBuilder sb = new StringBuilder(10);
        sb.append(type);
        for (int i = 0; i < 9; i++) {
            sb.append((mode & bits[i]) != 0 ? chars[i % 3] : '-');
        }
        return sb.toString();
    }

    private static String formatArchiveMtime(TarArchiveEntry entry) {
        Date time = entry.getModTime();
        if (time == null) {
            return null;
        }
        return MTIME_FORMAT.format(LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault()));
    }

    private List<ContainerFileVo> parseLs(String dir, String output) {
        List<ContainerFileVo> list = new ArrayList<>();
        if (StrUtil.isBlank(output)) {
            return list;
        }
        String base = dir.endsWith("/") ? dir : dir + "/";
        for (String line : output.split("\\r?\\n")) {
            if (StrUtil.isBlank(line) || line.startsWith("total ")) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 7 || !MODE_PATTERN.matcher(tokens[0]).matches()) {
                continue;
            }
            String mode = tokens[0];
            String size = tokens[4];
            int nameStart;
            String mtime;
            if (ISO_DATE_PATTERN.matcher(tokens[5]).matches()) {
                mtime = tokens[5] + " " + tokens[6];
                nameStart = 7;
            } else if (tokens.length >= 8) {
                mtime = tokens[5] + " " + tokens[6] + " " + tokens[7];
                nameStart = 8;
            } else {
                continue;
            }
            if (tokens.length <= nameStart) {
                continue;
            }
            String name = String.join(" ", Arrays.copyOfRange(tokens, nameStart, tokens.length));
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            ContainerFileVo vo = new ContainerFileVo();
            vo.setMode(mode);
            vo.setMtime(mtime);
            char kind = mode.charAt(0);
            if (kind == 'd') {
                vo.setType("dir");
            } else if (kind == 'l') {
                vo.setType("link");
            } else if (kind == '-') {
                vo.setType("file");
            } else {
                vo.setType("other");
            }
            if (vo.getType().equals("link")) {
                int arrow = name.indexOf(" -> ");
                if (arrow >= 0) {
                    vo.setLinkTarget(name.substring(arrow + 4));
                    name = name.substring(0, arrow);
                }
            }
            vo.setName(name);
            vo.setPath(base + name);
            if (!"dir".equals(vo.getType())) {
                try {
                    vo.setSize(Long.parseLong(size));
                } catch (NumberFormatException ignored) {
                    // 保持 size 为空
                }
            }
            list.add(vo);
        }
        list.sort(Comparator
                .comparing((ContainerFileVo f) -> !"dir".equals(f.getType()))
                .thenComparing(ContainerFileVo::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    /**
     * 文本预览：截断读取，避免大文件打爆内存。
     * <p>
     * 优先用容器内的 {@code head}；镜像里没有 head 时（distroless / scratch 等）从 Docker archive
     * 接口读取该文件内容。
     */
    public String previewFile(Host host, String containerId, String path) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            String byHead = previewByHead(client, containerId, path);
            if (byHead != null) {
                return byHead;
            }
            return new String(readFileByArchive(client, containerId, path, PREVIEW_MAX_BYTES), StandardCharsets.UTF_8);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 用容器内的 head 截断读取；容器没有 head 或执行失败时返回 null，由 archive 兜底。 */
    private String previewByHead(DockerClient client, String containerId, String path) {
        try {
            CommandResult result = execCapture(client, containerId,
                    List.of("head", "-c", String.valueOf(PREVIEW_MAX_BYTES), path));
            if (result.exitCode == 0) {
                return result.stdout;
            }
        } catch (RuntimeException e) {
            log.debug("容器 {} 执行 head 失败，改用 archive 接口: {}", containerId, e.getMessage());
        }
        return null;
    }

    /** 从 Docker archive 接口读取单文件内容（不依赖容器内的 cat / head）。 */
    private byte[] readFileByArchive(DockerClient client, String containerId, String path, int maxBytes) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new RuntimeException("文件不存在: " + path);
            }
            if (entry.isDirectory()) {
                throw new RuntimeException("这是一个目录: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while (out.size() < maxBytes) {
                len = tar.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
                if (len == -1) {
                    break;
                }
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + path, e);
        }
    }

    /**
     * 下载文件：从 Docker archive 接口取出该文件内容。
     * <p>
     * 不再依赖容器内的 {@code cat}，因此 distroless / scratch 等镜像同样可下载。
     */
    public void downloadFile(Host host, String containerId, String path, OutputStream out) {
        DockerClient client = dockerClientManager.getClient(host);
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                throw new RuntimeException("文件不存在: " + path);
            }
            if (entry.isDirectory()) {
                throw new RuntimeException("这是一个目录: " + path);
            }
            tar.transferTo(out);
        } catch (IOException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /** 下载目录：直接透传 docker archive 接口返回的 tar 流。 */
    public void downloadDirectory(Host host, String containerId, String path, OutputStream out) {
        DockerClient client = dockerClientManager.getClient(host);
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec()) {
            in.transferTo(out);
        } catch (java.io.IOException e) {
            throw new RuntimeException("打包下载失败: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ 写入 / 上传

    /**
     * 写入容器内文件（覆盖）或上传新文件：把内容打包成单文件 tar，经 Docker archive 接口复制进容器。
     * <p>
     * 不依赖容器内的 shell / cat，distroless / scratch 镜像同样可用；同名文件会被覆盖，已存在文件沿用原权限。
     *
     * @param path    容器内文件的绝对路径
     * @param content 文件字节内容
     */
    public void writeFile(Host host, String containerId, String path, byte[] content) {
        writeFile(host, containerId, path, content, false);
    }

    /**
     * 写入文件；{@code preserveOwner=true} 时沿用已存在文件的 uid/gid。
     * <p>
     * 主机文件管理必须开启：宿主机上的配置文件经常属于 www-data、mysql 等用户，
     * 以 root 覆盖会把属主改掉。
     */
    public void writeFile(Host host, String containerId, String path, byte[] content, boolean preserveOwner) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            // 已存在的文件沿用原权限；开启 preserveOwner 时连 uid/gid 一起沿用
            FileAttrs attrs = resolveFileAttrs(client, containerId, normalizePath(path), preserveOwner);
            writeInternal(client, containerId, path, content, attrs);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 用调用方解析好的权限/属主写入文件。
     * <p>
     * 主机上传新文件时用它继承父目录的属主，避免在用户目录下创建出 root:root 的文件。
     */
    public void writeFile(Host host, String containerId, String path, byte[] content,
                          int mode, long uid, long gid) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            writeInternal(client, containerId, path, content,
                    new FileAttrs(mode > 0 ? mode : 0644, uid, gid));
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 删除容器内文件 / 目录（递归）。
     * <p>
     * Docker API 没有删除容器内文件的接口，这里经 exec 在容器内执行 {@code rm -rf}；
     * distroless / scratch 等不含 rm 的镜像无法删除，只能通过容器终端操作。
     */
    public void deleteFile(Host host, String containerId, String path) {
        String target = normalizePath(path);
        if ("/".equals(target)) {
            throw new BusinessException("不能删除根目录");
        }
        if (PROTECTED_DELETE_PREFIXES.stream().anyMatch(target::startsWith)) {
            throw new BusinessException("禁止删除系统伪文件系统 " + target + "（/proc、/sys、/dev 内没有用户数据）");
        }
        if (PROTECTED_DELETE_PATHS.contains(target)) {
            throw new BusinessException("禁止删除系统关键路径 " + target + "（容器运行依赖，目录内的文件仍可按需删除）");
        }
        try {
            execOrThrow(host, containerId, List.of("rm", "-rf", target));
        } catch (BusinessException e) {
            String stderr = StrUtil.trimToEmpty(e.getMessage());
            throw new BusinessException(stderr.isBlank() ? "删除失败" : "删除失败: " + stderr);
        }
    }

    private void writeInternal(DockerClient client, String containerId, String path,
                               byte[] content, FileAttrs attrs) {
        String file = normalizePath(path);
        int idx = file.lastIndexOf('/');
        String dir = idx <= 0 ? "/" : file.substring(0, idx);
        String name = file.substring(idx + 1);
        if (name.isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }

        try {
            byte[] tar = buildSingleFileTar(name, content, attrs);
            client.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .withRemotePath(dir)
                    .exec();
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        }
    }

    /** 读取容器内已存在文件的权限位与属主；文件不存在或读取失败时返回默认值。 */
    private FileAttrs resolveFileAttrs(DockerClient client, String containerId, String path, boolean preserveOwner) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry != null && !entry.isDirectory()) {
                int mode = entry.getMode() & 0777;
                long uid = preserveOwner ? entry.getLongUserId() : 0L;
                long gid = preserveOwner ? entry.getLongGroupId() : 0L;
                return new FileAttrs(mode > 0 ? mode : 0644, uid, gid);
            }
        } catch (Exception e) {
            log.debug("读取容器 {} 文件 {} 属性失败，使用默认值: {}", containerId, path, e.getMessage());
        }
        return new FileAttrs(0644, 0L, 0L);
    }

    /** 归一化容器内路径：统一分隔符、去掉重复斜杠与 . / .. 片段，并保证以 / 开头。 */
    private static String normalizePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String seg : StrUtil.trimToEmpty(path).replace('\\', '/').trim().split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(seg);
        }
        return "/" + String.join("/", parts);
    }

    /** 把单文件内容打包成 tar，供 archive 接口写入容器。 */
    private static byte[] buildSingleFileTar(String name, byte[] content, FileAttrs attrs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096 + content.length);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            entry.setMode(attrs.mode());
            entry.setUserId(attrs.uid());
            entry.setGroupId(attrs.gid());
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private record FileAttrs(int mode, long uid, long gid) {
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 在容器内执行一条命令并返回退出码/输出；用于 Docker API 没有的文件操作（mkdir / mv / rm 等）。
     * <p>
     * 命令以 argv 数组传入，不经过 shell，路径里的空格与特殊字符不会被解释。
     */
    public CommandResult exec(Host host, String containerId, List<String> cmd) {
        DockerClient client = dockerClientManager.getClient(host);
        try {
            return execCapture(client, containerId, cmd);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    public void execOrThrow(Host host, String containerId, List<String> cmd) {
        CommandResult result = exec(host, containerId, cmd);
        if (result.exitCode() != 0) {
            String stderr = StrUtil.trimToEmpty(result.stderr());
            throw new BusinessException(stderr.isBlank()
                    ? "命令执行失败: " + String.join(" ", cmd)
                    : stderr);
        }
    }

    private CommandResult execCapture(DockerClient client, String containerId, List<String> cmd) {
        ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd(cmd.toArray(new String[0]))
                .exec();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            client.execStartCmd(exec.getId())
                    .withDetach(false)
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion();
            InspectExecResponse state = client.inspectExecCmd(exec.getId()).exec();
            int exitCode = state.getExitCodeLong() == null ? 0 : state.getExitCodeLong().intValue();
            return new CommandResult(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("命令执行被中断", e);
        }
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : id;
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        String n = names[0];
        return n != null && n.startsWith("/") ? n.substring(1) : n;
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * 交互式 exec 会话（供控制台 WebSocket 使用）。
     * 由创建者负责 {@link #close()}。
     */
    public static class ExecSession {
        private final DockerClient client;
        private final String execId;
        private final OutputStream stdin;
        private final ResultCallback.Adapter<Frame> callback;

        ExecSession(DockerClient client, String execId, OutputStream stdin, ResultCallback.Adapter<Frame> callback) {
            this.client = client;
            this.execId = execId;
            this.stdin = stdin;
            this.callback = callback;
        }

        public String getExecId() {
            return execId;
        }

        public void write(byte[] data) {
            try {
                synchronized (stdin) {
                    stdin.write(data);
                    stdin.flush();
                }
            } catch (Exception e) {
                throw new RuntimeException("写入控制台失败: " + e.getMessage(), e);
            }
        }

        public void resize(int cols, int rows) {
            try {
                client.resizeExecCmd(execId).withSize(rows, cols).exec();
            } catch (Exception e) {
                log.debug("调整控制台大小失败: {}", e.getMessage());
            }
        }

        public void close() {
            IOUtils.closeQuietly(stdin);
            try {
                callback.close();
            } catch (Exception ignored) {
                // ignore
            }
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 打开交互式 shell 会话。TTY 模式下 stdin/stdout 为原始字节流，直接对接前端 xterm。
     *
     * @param onOutput 收到容器输出时的回调（原始字节）
     */
    public ExecSession openExecSession(Host host, String containerId, String shell,
                                       java.util.function.Consumer<byte[]> onOutput, Runnable onComplete) {
        DockerClient client = dockerClientManager.getClient(host);
        String sh = StrUtil.blankToDefault(shell, "/bin/sh");
        try {
            ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
                    .withCmd(sh)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(true)
                    .exec();

            java.io.PipedOutputStream stdinOut = new java.io.PipedOutputStream();
            java.io.PipedInputStream stdinIn = new java.io.PipedInputStream(stdinOut, 8192);

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame != null && frame.getPayload() != null) {
                        onOutput.accept(frame.getPayload());
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        super.onComplete();
                    } finally {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }
            };

            client.execStartCmd(exec.getId())
                    .withTty(true)
                    .withStdIn(stdinIn)
                    .exec(callback);

            return new ExecSession(client, exec.getId(), stdinOut, callback);
        } catch (Exception e) {
            IOUtils.closeQuietly(client);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e.getMessage(), e);
        }
    }
}
