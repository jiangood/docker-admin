package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
import io.github.jiangood.docker.admin.config.DockerSettings;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.DeployLogRepository;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.admin.dto.ImageConfigMetaVo;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.DeployLog;
import io.github.jiangood.docker.admin.entity.DeployTrigger;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.registry.RegistryV2Client;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.modules.logviewer.config.FileLogConfig;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppService extends BaseService<App> {

    private static final Logger fileLog = FileLog.logger();

    /** 可变标签：镜像内容会随推送变化，本地同名镜像无法证明与仓库一致 */
    private static final String LATEST_TAG = "latest";

    /**
     * 容器启停删的兜底超时：docker-java 的 responseTimeout 是 1 小时（拉镜像需要），
     * 但这类命令本该秒回，宿主机存储卡死时必须尽快失败，不能让部署线程一直挂着。
     * <p>
     * 停止的预算不在这里：它必须比「系统参数 - 容器运行」里的宽限期更长，
     * 见 {@link DockerSettings#budget()}。
     */
    private static final Duration CONTAINER_START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONTAINER_REMOVE_TIMEOUT = Duration.ofSeconds(60);

    /** 部署前探测 bind 源目录的超时（仅本机部署时探测） */
    private static final Duration BIND_CHECK_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 定时更新检查的兜底超时。平台只读注册中心的 manifest，通常几秒内返回；
     * 注册中心不可达时不能把 Quartz 线程一直挂住。
     */
    private static final Duration SCHEDULED_UPDATE_QUERY_TIMEOUT = Duration.ofSeconds(30);

    /** 定时检查失败后的重试间隔：不按完整检查间隔等待，避免一次网络抖动拖一整天 */
    private static final Duration SCHEDULED_UPDATE_RETRY_DELAY = Duration.ofMinutes(10);

    Set<String> deployingList = ConcurrentHashMap.newKeySet();

    private final AppRepository appRepository;
    private final HostRepository hostRepository;
    private final DeployLogRepository deployLogRepository;
    private final DockerSettings dockerSettings;

    @Resource
    DockerClientManager dockerManager;

    @Resource
    ImageTagService imageTagService;

    @Resource
    ProjectService projectService;

    @Resource
    private RegistryService registryService;

    @Resource
    FileLogConfig fileLogConfig;

    @Resource
    TaskLogRegistry taskLogRegistry;

    /**
     * 保存应用：新建走 create，编辑走 update。
     */
    @Transactional
    public App saveApp(App input, List<String> updateFields) {
        // 前端未选择组织机构时会提交空对象 {}，此时 id 为空，需归一为 null，
        // 否则 Hibernate 会把空对象当作未保存的瞬态实体而报错
        if (input.getSysOrg() == null || StrUtil.isBlank(input.getSysOrg().getId())) {
            input.setSysOrg(null);
        }
        App saved;
        if (StrUtil.isBlank(input.getId())) {
            saved = create(input);
        } else {
            saved = update(input, updateFields);
        }
        return saved;
    }

    @Async
    public void deploy(App app) {
        doDeploy(app, false, DeployTrigger.MANUAL);
    }

    @Async
    public void deploy(App app, boolean forcePull) {
        doDeploy(app, forcePull, DeployTrigger.MANUAL);
    }

    @Async
    public void deploy(App app, boolean forcePull, DeployTrigger trigger) {
        doDeploy(app, forcePull, trigger);
    }

    /**
     * 部署应用。
     *
     * @param forcePull 是否强制从仓库拉取镜像；为 false 时若本地已存在同名镜像则跳过拉取，直接使用本地镜像。
     *                  但 {@code latest} 是可变标签，本地同名镜像无法证明是最新内容，因此总是重新拉取
     * @param trigger   本次部署的触发方式，仅用于写部署记录，便于在「部署日志」里区分来源
     */
    private void doDeploy(App app, boolean forcePull, DeployTrigger trigger) {
        Assert.notNull(app, "应用不存在");
        // 用局部变量保存 id：下面重新加载应用时 app 可能为 null，
        // finally 里再用 app.getId() 会 NPE，导致「部署中」标记永远清不掉
        String appId = app.getId();
        deployingList.add(appId);
        DockerClient client = null;
        DeployLog deployLog = null;
        // saveAndFlush 抛异常时实体的 id 已被 @PrePersist 填好，不能再用 isNew() 判断是否落库
        boolean logSaved = false;
        boolean success = false;
        try {
            // 每次部署落一条独立记录，并以其 id 作为日志文件名：前端「部署日志」按部署记录
            // 列表展示，各次部署的日志互不混在一起（不再用 appId 绑定日志文件）。
            app.setUpdateTime(LocalDateTime.now());
            appRepository.save(app);
            app = appRepository.findById(app.getId()).orElse(null); // 确保关联对象都取出来

            deployLog = new DeployLog();
            deployLog.setAppId(app.getId());
            deployLog.setAppName(app.getName());
            deployLog.setImageTag(app.getImageTag());
            deployLog.setTriggerType(trigger == null ? DeployTrigger.MANUAL : trigger);
            // saveAndFlush：立即拿到底层生成的 id，绑定日志文件时才有值（与 BuildLogService.saveLog 一致）
            deployLog = deployLogRepository.saveAndFlush(deployLog);
            logSaved = true;

            FileLog.bind(deployLog.getId());
            // 登记为运行中的任务：前端「部署日志」用 WebSocket 实时 tail 本文件，
            // 未登记时 WS 会立刻判定任务已结束并断开（与构建 / 镜像同步一致）。
            taskLogRegistry.start(deployLog.getId());
            fileLog.info("部署阶段开始");
            Host host = app.getHost();

            // 镜像：tag 为空时按 latest 处理，与定时更新检查阶段保持一致
            String tag = StrUtil.blankToDefault(app.getImageTag(), LATEST_TAG);
            String repo = StrUtil.blankToDefault(ImageUrlUtils.repoOf(app.getImageUrl()), app.getImageUrl());
            String image = ImageUrlUtils.full(repo, tag);

            client = getClient(host, app.getImageUrl());
            // 供 lambda 捕获：client 在 try 里赋值，本身不是 effectively final
            final DockerClient dc = client;


            // latest 是可变标签：同样的 tag 可能已经指向新镜像，跳过拉取会继续用旧内容，
            // 构建成功后的自动部署尤其如此，因此无论是否勾选强制拉取都要重新拉取。
            boolean mutableTag = LATEST_TAG.equals(tag);
            boolean localExists = imageExists(client, image);
            if (!forcePull && !mutableTag && localExists) {
                fileLog.info("本地已存在镜像 {}，跳过拉取（如需更新请勾选强制拉取）", image);
            } else {
                if (mutableTag && !forcePull) {
                    fileLog.info("镜像 {} 使用可变标签 latest，强制重新拉取", image);
                } else {
                    fileLog.info("开始拉取镜像 {}", image);
                }
                // 回调的 logId 必须是本次部署记录 id：DefaultCallback 在 docker 流线程上
                // 重新绑定 MDC，传 appId 会把拉取进度写进 appId.log（前端并不展示该文件）。
                client.pullImageCmd(image)
                        .exec(new DefaultCallback<PullResponseItem>(deployLog.getId()))
                        .awaitCompletion();
                fileLog.info("拉取完成 {}", image);
            }


            fileLog.info("开始部署镜像 {}", image);
            App.AppConfig cfg = app.getConfig();

            // 端口 / 卷完全以应用配置为准：镜像声明只在新建应用时作为默认值，用户可以自由增删改
            List<String> portSpecs = portsFromConfig(cfg);
            List<String> volumePaths = volumesFromConfig(cfg);


            List<Container> containers = getContainer(app.getName(), client);


            for (Container container : containers) {
                fileLog.info("容器状态 {}", container.getState());
                String oldContainerId = container.getId();
                if (container.getState().equals("running")) {
                    fileLog.info("停止容器{}", container.getNames());
                    // 宽限期来自「系统参数 - 容器运行」，默认 60 秒（docker-java 不传时写死 10 秒）
                    int stopGraceSeconds = dockerSettings.graceSeconds();
                    dockerManager.callWithTimeout(dockerSettings.budget(), "停止容器 " + oldContainerId, () -> {
                        dc.stopContainerCmd(oldContainerId).withTimeout(stopGraceSeconds).exec();
                        return null;
                    });
                }
                fileLog.info("删除容器{}", container.getNames());
                dockerManager.callWithTimeout(CONTAINER_REMOVE_TIMEOUT, "删除容器 " + oldContainerId, () -> {
                    dc.removeContainerCmd(oldContainerId).exec();
                    return null;
                });
            }


            HostConfig hostConfig = new HostConfig();

            // 网络、 端口
            List<ExposedPort> exposedPorts = new ArrayList<>();
            {
                if (StrUtil.isNotBlank(cfg.getNetworkMode())) {
                    hostConfig.withNetworkMode(cfg.getNetworkMode());
                }

                // 仅桥接模式下使用端口映射
                if (StrUtil.isBlank(cfg.getNetworkMode()) || cfg.getNetworkMode().equals("bridge")) {
                    Ports ports = new Ports();

                    for (String spec : portSpecs) {
                        ExposedPort e = ExposedPort.parse(spec);
                        exposedPorts.add(e);

                        App.PortBinding binding = findPortBinding(cfg, e);
                        if (binding != null && binding.getPublicPort() != null) {
                            ports.bind(e, Ports.Binding.bindPort(binding.getPublicPort()));
                        }
                    }

                    hostConfig.withPortBindings(ports);
                }

            }


            // 文件路径绑定：按应用配置挂载
            List<Bind> binds = new ArrayList<>();

            for (String path : volumePaths) {
                App.BindConfig bindConfig = findBindConfig(cfg, path);
                if (bindConfig == null || StrUtil.isBlank(bindConfig.getPublicVolume())) {
                    continue;
                }
                // /host:/container:ro
                AccessMode accessMode = Boolean.TRUE.equals(bindConfig.getReadOnly()) ? AccessMode.ro : AccessMode.rw;
                binds.add(new Bind(bindConfig.getPublicVolume(), new Volume(path), accessMode));
            }
            hostConfig.withBinds(binds);

            // 本机部署时先探测 bind 源目录：目录若位于无响应的网络存储（NFS 等）上，宿主机内核会把
            // stat 阻塞成 D 状态，docker start 也跟着永远不返回。远端主机看不到对方的文件系统，
            // 只能靠下面启动命令的超时兜底。
            if (host == null || host.isLocalDocker()) {
                checkBindSources(binds);
            }


            // 环境变量
            List<String> envs = new ArrayList<>();
            if (cfg.getEnvs() != null) {
                for (App.EnvVar e : cfg.getEnvs()) {
                    if (e == null || StrUtil.isBlank(e.getName())) {
                        continue;
                    }
                    envs.add(e.getName() + "=" + StrUtil.nullToEmpty(e.getValue()));
                }
            }

            // 用户自定义 labels（docker run --label）：先放用户标签，再放平台编排标签，
            // 保证 app.name 等平台标签不被覆盖（应用容器按 app.name 查找/启停/删除）
            Map<String, String> labels = new HashMap<>();
            if (cfg.getLabels() != null) {
                for (App.Label l : cfg.getLabels()) {
                    if (l == null || StrUtil.isBlank(l.getName())) {
                        continue;
                    }
                    labels.put(l.getName(), StrUtil.nullToEmpty(l.getValue()));
                }
                if (!labels.isEmpty()) {
                    fileLog.info("用户自定义 labels={}", labels);
                }
            }
            labels.putAll(dockerManager.getAppLabelFilter(app.getName()));


            // 是否自动启动
            hostConfig.withRestartPolicy(RestartPolicy.onFailureRestart(5));

            hostConfig.withPrivileged(true);


            // 设备请求（GPU 等）：直接透传 HostConfig.DeviceRequests
            if (cfg.getDeviceRequests() != null && !cfg.getDeviceRequests().isEmpty()) {
                List<DeviceRequest> deviceRequests = cfg.getDeviceRequests().stream()
                        .filter(Objects::nonNull)
                        .map(r -> new DeviceRequest()
                                .withDriver(r.getDriver())
                                .withCount(r.getCount())
                                .withCapabilities(r.getCapabilities() != null && !r.getCapabilities().isEmpty()
                                        ? r.getCapabilities() : List.of(List.of("gpu"))))
                        .toList();
                hostConfig.withDeviceRequests(deviceRequests);
                fileLog.info("设备请求 {}", deviceRequests);
            }


            // hosts，ip域名映射, 支持两种格式， 1. ip 域名 2.域名:ip
            String hosts = cfg.getExtraHosts();
            if (StrUtil.isNotBlank(hosts)) {
                List<String> list = StrUtil.splitTrim(hosts, " ");
                fileLog.info("hosts={}", list);
                hostConfig.withExtraHosts(list.toArray(new String[list.size()]));
            }


            // cpu，限制

            // 日志限制
            LogConfig logConfig = new LogConfig(LogConfig.LoggingType.DEFAULT, new HashMap<>());
            logConfig.getConfig().put("max-size", "200m");
            hostConfig.withLogConfig(logConfig);


            fileLog.info("主机配置{}", hostConfig.getBinds());
            CreateContainerCmd containerCmd = client.createContainerCmd(image);
            containerCmd
                    .withName(app.getName())
                    .withLabels(labels)
                    .withHostConfig(hostConfig)
                    .withExposedPorts(exposedPorts) // 使用应用配置中的端口
                    .withEnv(envs);

            String cmd = app.getConfig().getCmd();
            if (StrUtil.isNotEmpty(cmd)) {
                List<String> cmds = StrUtil.splitTrim(cmd, " ");
                containerCmd.withCmd(cmds);
            }


            CreateContainerResponse response = containerCmd.exec();


            fileLog.info("创建容器{}", response);

            String containerId = response.getId();

            // 先打日志再启动：宿主机存储卡住时 start 不会返回，日志停在哪一步就是卡在哪一步
            fileLog.info("开始启动容器 {}", containerId);
            try {
                dockerManager.callWithTimeout(CONTAINER_START_TIMEOUT, "启动容器 " + containerId, () -> {
                    dc.startContainerCmd(containerId).exec();
                    return null;
                });
            } catch (IllegalStateException e) {
                throw new IllegalStateException(e.getMessage()
                        + "。宿主机很可能卡在 bind 挂载（" + hostConfig.getBinds() + "）或 docker 守护进程无响应，"
                        + "请登录宿主机检查该目录所在存储（如 NFS 挂载）与 docker 状态", e);
            }


            fileLog.info("启动容器");
            fileLog.info("部署阶段结束");
            success = true;
        } catch (Exception e) {
            fileLog.error("部署失败", e);
        } finally {
            // 先收尾数据库记录，再用内层 try/finally 保证「部署中」标记无论记录成败都会清掉。
            // 之前记录保存失败会在 finally 里再次抛出，后面的 remove 永远执行不到，
            // 应用会一直卡在「部署中」，定时更新也从下一轮开始被永久跳过。
            try {
                if (logSaved) {
                    deployLog.setSuccess(success);
                    deployLog.setCompleteTime(LocalDateTime.now());
                    deployLogRepository.save(deployLog);
                    // 标记任务结束：前端 WS 收到后补发剩余内容并断开
                    taskLogRegistry.finish(deployLog.getId());
                }
            } catch (Exception e) {
                log.error("记录部署结果失败，应用 {} 的这次部署没有写入部署记录", appId, e);
            } finally {
                IOUtils.closeQuietly(client);
                deployingList.remove(appId);
                FileLog.unbind();
            }
        }

    }

    /**
     * 某应用的部署记录，最新在前，并标记各自是否已有独立日志文件、耗时。
     * 更早版本把同一应用的多次部署日志合并写在 {@code appId.log} 里，这些历史记录没有
     * 独立文件，前端据此提示「无独立日志」。
     */
    public List<DeployLog> listDeployLogs(String appId) {
        List<DeployLog> list = deployLogRepository.findByAppIdOrderByCreateTimeDesc(appId);
        File logDir = new File(fileLogConfig.getLogPath());
        for (DeployLog log : list) {
            log.setLogExists(new File(logDir, log.getId() + ".log").exists());
            if (log.getCreateTime() != null && log.getCompleteTime() != null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), log.getCompleteTime()).toMillis());
            }
        }
        return list;
    }

    // ---------------- 定时更新 ----------------

    /**
     * 定时更新一轮：只检查开启且到期的应用。
     * <p>
     * 任务每分钟触发一次，间隔粒度由应用自己配置（与网站监控一致）；
     * 绝大多数轮次只会读一遍配置，开销可忽略。
     */
    public ScheduledUpdateSummary runDueScheduledUpdates() {
        List<App> apps = appRepository.findAllByScheduledUpdateIsTrue();
        int checked = 0;
        int updated = 0;
        int failed = 0;

        for (App app : apps) {
            if (!isScheduledUpdateDue(app)) {
                continue;
            }
            if (isDeploying(app.getId())) {
                log.info("应用「{}」正在部署，跳过本轮定时更新检查", app.getName());
                continue;
            }

            checked++;
            ScheduledUpdateCheck check = checkScheduledUpdate(app);
            if (!check.success()) {
                failed++;
            }
            if (check.deploy() && !isDeploying(app.getId())) {
                updated++;
                // 通过代理调用，保证 @Async 生效
                SpringUtil.getBean(getClass()).deployScheduledUpdate(app.getId());
            }
        }

        return new ScheduledUpdateSummary(checked, updated, failed);
    }

    public boolean isDeploying(String appId) {
        return appId != null && deployingList.contains(appId);
    }

    /**
     * 检查一个应用是否有新镜像。
     * <p>
     * 只处理运行中的容器：停止的应用等下次启动后再说，避免把用户刻意停掉的容器自动拉起。
     * 检查由平台直接访问注册中心（读 manifest，不下载镜像层），
     * 用「注册中心里该 tag 当前的 manifest 摘要」与「本地镜像的 RepoDigests / 镜像 ID」比较，
     * 因此上次部署失败、本地还是旧镜像时，下一轮还会继续尝试。
     */
    public ScheduledUpdateCheck checkScheduledUpdate(App app) {
        String message;
        boolean success = true;
        boolean deploy = false;

        try {
            String tag = StrUtil.blankToDefault(app.getImageTag(), LATEST_TAG);
            String repo = StrUtil.blankToDefault(ImageUrlUtils.repoOf(app.getImageUrl()), app.getImageUrl());
            String image = ImageUrlUtils.full(repo, tag);

            DockerClient client = getClient(app.getHost(), app.getImageUrl());
            try {
                // 容器查询要兜底超时：后台任务不能被单个失联主机卡住
                Container container = dockerManager.callWithTimeout(SCHEDULED_UPDATE_QUERY_TIMEOUT,
                        "查询容器 " + app.getName(),
                        () -> getContainer(app.getName(), client).stream().findFirst().orElse(null));

                if (container == null) {
                    message = "容器未部署，跳过";
                } else if (!"running".equalsIgnoreCase(container.getState())) {
                    message = "容器未运行，跳过";
                } else {
                    log.info("应用「{}」定时检查注册中心镜像 {}", app.getName(), image);
                    String remoteDigest = remoteImageDigest(repo, tag);
                    Set<String> localDigests = localImageDigests(client, container.getImageId());
                    if (localDigests.contains(remoteDigest)) {
                        message = "已是最新";
                    } else {
                        message = "发现新版本，开始部署";
                        deploy = true;
                    }
                }
            } finally {
                IOUtils.closeQuietly(client);
            }
        } catch (Exception e) {
            success = false;
            message = "检查失败：" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getMessage()), 800);
            log.warn("应用「{}」定时更新检查失败：{}", app.getName(), e.toString());
        }

        applyScheduledUpdateResult(app.getId(), success, message);
        log.info("应用「{}」定时更新检查：{}", app.getName(), message);
        return new ScheduledUpdateCheck(message, success, deploy);
    }

    /**
     * 查询注册中心里 tag 当前指向的顶层 manifest 摘要（与 docker pull 记入本地 RepoDigests 的值一致）。
     * <p>
     * 只读 manifest，不下载镜像层；地址与凭据按
     * {@link RegistryService#v2ClientFor(String)} 解析，Docker Hub 等未登记的公共仓库匿名访问。
     */
    private String remoteImageDigest(String repo, String tag) {
        RegistryV2Client v2 = registryService.v2ClientFor(repo);
        String digest = v2.fetchManifestDigest(ImageUrlUtils.repoNameOf(repo), tag);
        if (StrUtil.isBlank(digest)) {
            throw new IllegalStateException("注册中心未返回镜像摘要：" + repo + ":" + tag);
        }
        return digest.toLowerCase(Locale.ROOT);
    }

    /**
     * 本地镜像可用于比较的摘要集合。
     * <p>
     * 经典镜像存储的镜像 ID 是 image config 摘要，与仓库的 manifest 摘要不同，
     * 必须读 {@code RepoDigests}；containerd 镜像存储的镜像 ID 本身就是 manifest 摘要。
     * 两种都收进集合，避免依赖宿主机用的是哪种存储。
     */
    private Set<String> localImageDigests(DockerClient client, String imageId) {
        var local = dockerManager.callWithTimeout(SCHEDULED_UPDATE_QUERY_TIMEOUT,
                "检查本地镜像 " + imageId,
                () -> client.inspectImageCmd(imageId).exec());

        Set<String> digests = new HashSet<>();
        if (StrUtil.isNotBlank(local.getId())) {
            digests.add(local.getId().toLowerCase(Locale.ROOT));
        }
        if (local.getRepoDigests() != null) {
            for (String repoDigest : local.getRepoDigests()) {
                String value = StrUtil.nullToEmpty(repoDigest);
                int at = value.lastIndexOf('@');
                if (at >= 0 && at < value.length() - 1) {
                    digests.add(value.substring(at + 1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return digests;
    }

    /**
     * 检查结束后只回写检查快照，避免把任务线程里加载的旧应用对象整体保存，
     * 覆盖用户在检查期间做的修改。
     */
    private void applyScheduledUpdateResult(String appId, boolean success, String message) {
        App latest = appRepository.findById(appId).orElse(null);
        if (latest == null) {
            return;
        }
        latest.setScheduledUpdateLastCheckTime(LocalDateTime.now());
        latest.setScheduledUpdateLastSuccess(success);
        latest.setScheduledUpdateLastResult(StrUtil.maxLength(message, 990));
        appRepository.save(latest);
    }

    /**
     * 到期判断：从未检查过，或距上次检查已满配置间隔。
     * 上次检查失败时最多等 10 分钟就重试，避免一次网络抖动拖过一个完整间隔。
     */
    private boolean isScheduledUpdateDue(App app) {
        LocalDateTime last = app.getScheduledUpdateLastCheckTime();
        if (last == null) {
            return true;
        }

        int minutes = app.getScheduledUpdateIntervalMinutes() == null
                ? App.DEFAULT_SCHEDULED_UPDATE_INTERVAL_MINUTES
                : Math.max(1, app.getScheduledUpdateIntervalMinutes());
        Duration interval = Duration.ofMinutes(minutes);
        if (Boolean.FALSE.equals(app.getScheduledUpdateLastSuccess())
                && interval.compareTo(SCHEDULED_UPDATE_RETRY_DELAY) > 0) {
            interval = SCHEDULED_UPDATE_RETRY_DELAY;
        }
        return Duration.between(last, LocalDateTime.now()).compareTo(interval) >= 0;
    }

    /**
     * 定时检查发现新镜像后异步部署。
     * <p>
     * 检查阶段只读了注册中心，本地 tag 很可能仍指向旧镜像，因此这里强制拉取，
     * 确保部署用的是刚检测到的新镜像。
     */
    @Async
    public void deployScheduledUpdate(String appId) {
        if (isDeploying(appId)) {
            log.info("应用 {} 已在部署中，跳过本次定时更新部署", appId);
            return;
        }
        App app = appRepository.findById(appId).orElse(null);
        if (app == null) {
            return;
        }
        doDeploy(app, true, DeployTrigger.SCHEDULE);
    }

    /**
     * 保存定时更新设置。开启时重置上次检查时间，下一轮任务会立即检查一次。
     */
    @Transactional
    public App updateScheduledUpdate(String id, boolean enabled, Integer intervalMinutes) {
        App app = appRepository.findById(id).orElse(null);
        Assert.notNull(app, "应用不存在");

        int minutes = intervalMinutes == null
                ? App.DEFAULT_SCHEDULED_UPDATE_INTERVAL_MINUTES
                : intervalMinutes;
        Assert.isTrue(minutes >= 1 && minutes <= 60 * 24 * 30, "检查间隔必须在 1 分钟到 30 天之间");

        app.setScheduledUpdate(enabled);
        app.setScheduledUpdateIntervalMinutes(minutes);
        if (enabled) {
            app.setScheduledUpdateLastCheckTime(null);
            app.setScheduledUpdateLastSuccess(null);
            app.setScheduledUpdateLastResult(null);
        }
        return appRepository.save(app);
    }

    public record ScheduledUpdateSummary(int checked, int updated, int failed) {
        public String describe() {
            return "检查 " + checked + " 个应用，更新 " + updated + " 个，失败 " + failed + " 个";
        }
    }

    public record ScheduledUpdateCheck(String message, boolean success, boolean deploy) {
    }

    /**
     * 本地是否已存在指定镜像（含 tag）。镜像不存在或查询失败时按不存在处理，交由后续 pull 兜底。
     */
    private boolean imageExists(DockerClient client, String image) {
        try {
            client.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            fileLog.warn("查询本地镜像失败，将执行拉取: {}", image, e);
            return false;
        }
    }

    public void stop(String id) {
        App app = appRepository.findById(id).orElse(null);

        DockerClient client = dockerManager.getClient(app.getHost());
        try {
            List<Container> list = getContainer(app.getName(), client);
            int stopGraceSeconds = dockerSettings.graceSeconds();

            for (Container container : list) {
                dockerManager.callWithTimeout(dockerSettings.budget(), "停止容器 " + container.getId(), () -> {
                    client.stopContainerCmd(container.getId()).withTimeout(stopGraceSeconds).exec();
                    return null;
                });
            }
        } finally {
            IOUtils.closeQuietly(client);
        }
    }


    public void start(String id) {
        App app = appRepository.findById(id).orElse(null);

        DockerClient client = dockerManager.getClient(app.getHost());
        try {
            List<Container> list = getContainer(app.getName(), client);

            for (Container container : list) {
                client.startContainerCmd(container.getId()).exec();
            }
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    public App rename(String appId, String newName) {
        App app = appRepository.findById(appId).orElse(null);
        Assert.notNull(app, "app不存在");
        //判断名字是否相同进行部署
        if (newName.equals(app.getName())) {
            return app;
        }
        // 改名会重建容器
        this.deleteContainer(app);
        app.setName(newName);
        App saved = appRepository.save(app);

        this.deploy(app, false, DeployTrigger.RENAME);

        return saved;
    }

    private List<Container> getContainer(String name, DockerClient client) {
        Map<String, String> labels = dockerManager.getAppLabelFilter(name);
        List<Container> list = client.listContainersCmd()
                .withLabelFilter(labels)
                .withShowAll(true).exec();
        return list;
    }


    public ContainerVo getContainerVo(App app) {
        Container container = getContainer(app);
        ContainerVo data = new ContainerVo(container);

        if (deployingList.contains(app.getId())) {
            data.setState("deploying");
            data.setStatus("部署中...");
        }


        return data;
    }

    public Container getContainer(App app) {
        DockerClient client = dockerManager.getClient(app.getHost());

        String name = app.getName();

        Map<String, String> labels = dockerManager.getAppLabelFilter(name);
        try {


            List<Container> list = client.listContainersCmd().withLabelFilter(labels).withShowAll(true).exec();
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (Exception e) {
            throw new BusinessException("查询容器状态失败", e);
        } finally {
            IOUtils.closeQuietly(client);
        }
        return null;
    }

    @Transactional
    public void deleteApp(String id) {
        // 远程删除应用
        App app = appRepository.findById(id).orElse(null);
        deleteContainer(app);

        appRepository.deleteById(id);
    }

    /**
     * 强制删除应用数据：用于容器已不可达、无法正常删除的兜底场景。
     */
    @Transactional
    public void forceDeleteApp(String id) {
        deleteById(id);
    }


    /**
     * 该应用镜像可用的版本（来自镜像标签表），倒序。
     */
    public List<String> getImageVersions(String imageUrl) {
        return imageTagService.tags(imageUrl);
    }

    public void updateAppVersion(String id, String tag) {
        updateAppVersion(id, tag, false);
    }

    public void updateAppVersion(String id, String tag, boolean forcePull) {
        Assert.hasLength(tag, "tag不能为空");
        // 远程删除应用
        App app = appRepository.findById(id).orElse(null);
        app.setImageTag(tag);
        appRepository.save(app);

        SpringUtil.getBean(getClass()).deploy(app, forcePull);
    }

    private void deleteContainer(App app) {
        DockerClient client = dockerManager.getClient(app.getHost());
        try {
            Map<String, String> labels = dockerManager.getAppLabelFilter(app.getName());
            List<Container> list = client.listContainersCmd().withLabelFilter(labels).withShowAll(true).exec();
            log.info("已有容器个数 {}", list.size());
            int stopGraceSeconds = dockerSettings.graceSeconds();

            list.forEach(c -> {
                if (c.getState().equals("running")) {
                    log.info("正在停止容器 {}", c);
                    // 与主机维度的容器操作一致：容器卡在不可中断状态时不能让请求挂满一小时
                    dockerManager.callWithTimeout(dockerSettings.budget(), "停止容器 " + c.getId(), () -> {
                        client.stopContainerCmd(c.getId()).withTimeout(stopGraceSeconds).exec();
                        return null;
                    });
                }
                log.info("正在删除容器 {}", c);
                try {
                    dockerManager.callWithTimeout(CONTAINER_REMOVE_TIMEOUT, "删除容器 " + c.getId(), () -> {
                        client.removeContainerCmd(c.getId()).exec();
                        return null;
                    });
                } catch (NotFoundException e) {
                    // 已经被删掉了，按成功处理
                    log.info("容器 {} 已不存在，跳过删除", c.getId());
                }
            });
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    public App updateConfig(String id, App.AppConfig appConfig) {
        App app = appRepository.findById(id).orElse(null);
        Assert.notNull(app, "应用不存在");
        normalizeConfig(appConfig);
        app.setConfig(appConfig);

        app = appRepository.save(app);
        return app;
    }

    /**
     * 容器配置元数据：镜像表声明的端口/卷。
     * 声明只用于新建应用时预填默认值，用户可自由增删改。
     */
    public ImageConfigMetaVo getConfigMeta(App app) {
        return buildConfigMeta(app.getImageUrl(), app.getImageTag());
    }

    /**
     * 按镜像地址 + 版本构造容器配置元数据，供新建应用 / docker run 等尚无应用记录的场景使用。
     */
    public ImageConfigMetaVo getImageConfigMeta(String imageUrl, String imageTag) {
        return buildConfigMeta(imageUrl, imageTag);
    }

    /**
     * 构造容器配置元数据：仅返回镜像标签表中声明的端口/卷，不含任何已保存的映射值。
     */
    private ImageConfigMetaVo buildConfigMeta(String imageUrl, String imageTag) {
        ImageConfigMetaVo vo = new ImageConfigMetaVo();
        vo.setImageUrl(imageUrl);

        ImageTag iv = imageTagService.find(imageUrl, imageTag).orElse(null);
        if (iv == null) {
            return vo;
        }

        if (iv.getExposedPorts() != null) {
            for (String declared : iv.getExposedPorts()) {
                ExposedPort ep = ExposedPort.parse(declared);
                ImageConfigMetaVo.PortMeta pm = new ImageConfigMetaVo.PortMeta();
                pm.setPrivatePort(ep.getPort());
                pm.setProtocol(ep.getProtocol().toString().toUpperCase());
                vo.getPorts().add(pm);
            }
        }

        if (iv.getVolumes() != null) {
            for (String declared : iv.getVolumes()) {
                ImageConfigMetaVo.VolumeMeta vm = new ImageConfigMetaVo.VolumeMeta();
                vm.setPrivateVolume(declared);
                vo.getVolumes().add(vm);
            }
        }

        return vo;
    }

    /**
     * 创建主机 docker 客户端。
     * <p>
     * 仅当镜像属于某个注册中心时才附带该注册中心的凭据，归属按镜像地址解析
     * （见 {@link RegistryService#forImageUrl}）——多注册中心并存时不能用系统默认值代替，
     * 否则镜像所在注册中心与默认值不一致就会匿名拉取，报 pull access denied。
     * <p>
     * 另一个原因：docker-java 会把客户端级凭据（registry.url/username/password）用于所有 pull，
     * 拉取 ghcr.io / Docker Hub 等公共镜像时带上注册中心账号密码会被直接拒绝（401/403）。
     */
    public DockerClient getClient(Host host, String imageUrl) {
        return dockerManager.getClient(host, registryService.forImageUrl(imageUrl));
    }

    /**
     * 部署前探测 bind 源目录是否可访问。
     * <p>
     * 返回值不参与判断：目录不存在时 docker 会自行创建，这里只借这次 stat 探测「会不会卡住」——
     * 目录位于无响应的网络存储（NFS 等）上时，stat 会阻塞在宿主机内核态，探测超时即为异常。
     */
    private void checkBindSources(List<Bind> binds) {
        for (Bind bind : binds) {
            String source = bind.getPath();
            if (StrUtil.isBlank(source)) {
                continue;
            }
            try {
                dockerManager.callWithTimeout(BIND_CHECK_TIMEOUT, "检查宿主机目录 " + source,
                        () -> new File(source).exists());
            } catch (IllegalStateException e) {
                throw new IllegalStateException(e.getMessage()
                        + "。该目录很可能位于无响应的网络存储（如 NFS）上，请先在宿主机上检查挂载状态", e);
            }
        }
    }

    private void normalizeConfig(App.AppConfig cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.getPorts() == null) {
            cfg.setPorts(new ArrayList<>());
        }
        if (cfg.getBinds() == null) {
            cfg.setBinds(new ArrayList<>());
        }
        if (cfg.getEnvs() == null) {
            cfg.setEnvs(new ArrayList<>());
        }
    }

    /**
     * 从应用配置中取出要暴露的端口（去重，格式 80/tcp）。
     */
    private static List<String> portsFromConfig(App.AppConfig cfg) {
        if (cfg == null || cfg.getPorts() == null) {
            return List.of();
        }
        return cfg.getPorts().stream()
                .filter(p -> p != null && p.getPrivatePort() != null)
                .map(p -> portKey(p.getPrivatePort(), p.getProtocol()))
                .distinct()
                .toList();
    }

    /**
     * 从应用配置中取出要挂载的容器路径（去重）。
     */
    private static List<String> volumesFromConfig(App.AppConfig cfg) {
        if (cfg == null || cfg.getBinds() == null) {
            return List.of();
        }
        return cfg.getBinds().stream()
                .filter(b -> b != null && StrUtil.isNotBlank(b.getPrivateVolume()))
                .map(App.BindConfig::getPrivateVolume)
                .distinct()
                .toList();
    }

    private App.PortBinding findPortBinding(App.AppConfig cfg, ExposedPort exposedPort) {
        if (cfg == null || cfg.getPorts() == null) {
            return null;
        }
        String key = portKey(exposedPort.getPort(), exposedPort.getProtocol().toString());
        return cfg.getPorts().stream()
                .filter(p -> p.getPrivatePort() != null && portKey(p.getPrivatePort(), p.getProtocol()).equals(key))
                .findFirst().orElse(null);
    }

    private App.BindConfig findBindConfig(App.AppConfig cfg, String privateVolume) {
        if (cfg == null || cfg.getBinds() == null) {
            return null;
        }
        String path = normalizeVolumePath(privateVolume);
        return cfg.getBinds().stream()
                .filter(b -> StrUtil.isNotBlank(b.getPrivateVolume())
                        && normalizeVolumePath(b.getPrivateVolume()).equals(path))
                .findFirst().orElse(null);
    }

    private static String portKey(Integer port, String protocol) {
        String p = StrUtil.isBlank(protocol) ? "tcp" : protocol.toLowerCase();
        return port + "/" + p;
    }

    private static String normalizeVolumePath(String path) {
        if (StrUtil.isBlank(path)) {
            return path;
        }
        String s = path.trim();
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @EventListener
    public void onBuildSuccess(BuildSuccessEvent event) {
        fileLog.info("构建成功，开始检测关联应用");
        BuildLog buildLog = event.getBuildLog();

        Project project = projectService.findById(buildLog.getProjectId()).orElse(null);
        if (project == null) {
            return;
        }
        String imageUrl = projectService.getFullImageUrl(project);
        if (StrUtil.isBlank(imageUrl)) {
            return;
        }

        List<App> list = appRepository.findAllByImageUrl(imageUrl);
        // 让注解生效
        AppService $this = SpringUtil.getBean(getClass());

        for (App app : list) {
            boolean auto = app.getAutoDeploy() != null && app.getAutoDeploy();
            if (!auto) {
                continue;
            }
            app.setImageTag(event.getTag());
            $this.deploy(app, false, DeployTrigger.BUILD);
        }
    }

    /**
     * 只修改简单信息， 其他信息设计到重新部署，如修改主机，比较复杂
     *
     * @param input
     */
    @Transactional
    public void updateBaseInfo(App input) {
        if (input.getSysOrg() == null || input.getSysOrg().getId() == null) {
            input.setSysOrg(null);
        }

        App old = appRepository.findById(input.getId()).orElse(null);
        Assert.notNull(old, "应用不存在");
        old.setSysOrg(input.getSysOrg());
        old.setRemark(input.getRemark());
        old.setImageUrl(input.getImageUrl());
        old.setImageTag(input.getImageTag());
        appRepository.save(old);
    }


    /**
     * 复制应用到指定主机。
     *
     * @param name 新应用名称；为空时自动生成（原名 + _copy）
     */
    public App copyApp(String appId, String hostId, String name) {
        App app = requireApp(appId);
        Host host = requireHost(hostId);

        String newName = StrUtil.isBlank(name) ? buildCopyName(app.getName()) : name.trim();
        assertNameAvailable(newName);

        return appRepository.save(buildCopiedApp(app, newName, host));
    }

    /**
     * 迁移应用：在原主机停止并移除容器，切换到目标主机后重新部署。
     * 应用记录（id、名称、容器配置、镜像等）保持不变。
     */
    public App migrateApp(String appId, String hostId) {
        App app = requireApp(appId);
        Host host = requireHost(hostId);
        Assert.isTrue(!host.getId().equals(app.getHost().getId()), "目标主机与当前主机相同，无需迁移");

        // 1. 在原主机停止并移除容器（此时 app.host 仍指向原主机）
        deleteContainer(app);

        // 2. 切换主机
        app.setHost(host);
        App saved = appRepository.save(app);

        // 3. 在目标主机重新部署（通过代理调用，保证 @Async 生效）
        SpringUtil.getBean(getClass()).deploy(saved, false, DeployTrigger.MIGRATE);
        return saved;
    }

    /**
     * 应用名称是否已被占用（供前端复制应用时先行校验）。
     */
    public boolean existsByName(String name) {
        return StrUtil.isNotBlank(name) && appRepository.existsByName(name.trim());
    }

    private App requireApp(String appId) {
        App app = appRepository.findById(appId).orElse(null);
        Assert.notNull(app, "应用不存在");
        return app;
    }

    private Host requireHost(String hostId) {
        Host host = hostRepository.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }

    /**
     * 校验名称可用：字母开头，且未被占用。
     */
    private void assertNameAvailable(String newName) {
        Assert.isTrue(StrUtil.isNotBlank(newName) && Character.isLetter(newName.charAt(0)), "应用名称必须以字母开头");
        Assert.isTrue(!appRepository.existsByName(newName), "应用名称已存在：" + newName);
    }

    /**
     * 依据原应用构造副本（不复制 id/name/host 及审计字段）。
     */
    private App buildCopiedApp(App app, String newName, Host host) {
        App newApp = new App();
        BeanUtils.copyProperties(app, newApp, "id", "name", "host", "createUser", "createTime",
                "updateUser", "updateTime", "logUrl", "config",
                // 复制出来的应用默认不开启定时更新，避免多出一份没人预期的自动拉取；间隔偏好保留
                "scheduledUpdate", "scheduledUpdateLastCheckTime",
                "scheduledUpdateLastSuccess", "scheduledUpdateLastResult");
        newApp.setName(newName);
        newApp.setHost(host);
        newApp.setScheduledUpdate(false);
        newApp.setScheduledUpdateIntervalMinutes(app.getScheduledUpdateIntervalMinutes());
        if (app.getConfig() != null) {
            newApp.setConfig(JsonTool.jsonToBean(JsonTool.toJsonQuietly(app.getConfig()), App.AppConfig.class));
        }
        return newApp;
    }

    private String buildCopyName(String name) {
        String candidate = name + "_copy";
        int index = 1;
        while (appRepository.existsByName(candidate)) {
            index++;
            candidate = name + "_copy" + index;
        }
        return candidate;
    }

}
