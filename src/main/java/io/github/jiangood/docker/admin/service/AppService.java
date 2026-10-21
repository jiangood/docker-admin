package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
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

    /** 隧道未关闭时业务拦截的响应码，前端据此区分业务拦截与容器故障 */
    private static final int TUNNEL_STILL_OPEN_CODE = 409;

    /** 可变标签：镜像内容会随推送变化，本地同名镜像无法证明与仓库一致 */
    private static final String LATEST_TAG = "latest";

    /**
     * 容器启停删的兜底超时：docker-java 的 responseTimeout 是 1 小时（拉镜像需要），
     * 但这类命令本该秒回，宿主机存储卡死时必须尽快失败，不能让部署线程一直挂着。
     */
    private static final Duration CONTAINER_START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONTAINER_STOP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONTAINER_REMOVE_TIMEOUT = Duration.ofSeconds(60);

    /** 部署前探测 bind 源目录的超时（仅本机部署时探测） */
    private static final Duration BIND_CHECK_TIMEOUT = Duration.ofSeconds(5);

    Set<String> deployingList = ConcurrentHashMap.newKeySet();

    private final AppRepository appRepository;
    private final HostRepository hostRepository;
    private final DeployLogRepository deployLogRepository;

    @Resource
    DockerClientManager dockerManager;

    @Resource
    ImageTagService imageTagService;

    @Resource
    ProjectService projectService;

    @Resource
    private RegistryService registryService;

    @Resource
    TunnelService tunnelService;

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
        deployingList.add(app.getId());
        DockerClient client = null;
        DeployLog deployLog = null;
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

            FileLog.bind(deployLog.getId());
            // 登记为运行中的任务：前端「部署日志」用 WebSocket 实时 tail 本文件，
            // 未登记时 WS 会立刻判定任务已结束并断开（与构建 / 镜像同步一致）。
            taskLogRegistry.start(deployLog.getId());
            fileLog.info("部署阶段开始");
            Host host = app.getHost();

            // 镜像
            String image = app.getImageUrl() + ":" + app.getImageTag();

            client = getClient(host, app.getImageUrl());
            // 供 lambda 捕获：client 在 try 里赋值，本身不是 effectively final
            final DockerClient dc = client;


            // latest 是可变标签：同样的 tag 可能已经指向新镜像，跳过拉取会继续用旧内容，
            // 构建成功后的自动部署尤其如此，因此无论是否勾选强制拉取都要重新拉取。
            boolean mutableTag = LATEST_TAG.equals(app.getImageTag());
            if (!forcePull && !mutableTag && imageExists(client, image)) {
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
                    dockerManager.callWithTimeout(CONTAINER_STOP_TIMEOUT, "停止容器 " + oldContainerId, () -> {
                        dc.stopContainerCmd(oldContainerId).exec();
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
                    .withLabels(dockerManager.getAppLabelFilter(app.getName()))
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
            if (deployLog != null) {
                deployLog.setSuccess(success);
                deployLog.setCompleteTime(LocalDateTime.now());
                deployLogRepository.save(deployLog);
                // 标记任务结束：前端 WS 收到后补发剩余内容并断开
                taskLogRegistry.finish(deployLog.getId());
            }
            IOUtils.closeQuietly(client);
            deployingList.remove(app.getId());
            FileLog.unbind();
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

            for (Container container : list) {
                client.stopContainerCmd(container.getId()).exec();
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
        // 改名会重建容器，且隧道登记的应用信息随之变化，要求先关闭隧道
        assertTunnelClosed(app, "修改应用名称");
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
        assertTunnelClosed(app, "删除");
        // 隧道必然已关闭，这里仅作兜底（防止历史数据里已关闭但客户端仍残留映射）
        tunnelService.removeAppTunnel(app);
        deleteContainer(app);

        appRepository.deleteById(id);
    }

    /**
     * 强制删除应用数据：用于容器已不可达、无法正常删除的兜底场景。
     * 与正常删除一样要求隧道已关闭——关闭隧道只依赖隧道客户端、不依赖 docker 主机，不会让用户卡住。
     */
    @Transactional
    public void forceDeleteApp(String id) {
        App app = appRepository.findById(id).orElse(null);
        assertTunnelClosed(app, "删除");
        // 隧道必然已关闭，这里仅作兜底（防止历史数据里已关闭但客户端仍残留映射）
        tunnelService.removeAppTunnel(app);
        deleteById(id);
    }

    /**
     * 隧道仍开启时禁止应用名称、容器配置、迁移、删除等变更：客户端上的隧道以应用信息
     * （名称 / 主机 IP / 主机端口）转发，直接操作会留下失效隧道或把访问地址指错，
     * 因此要求用户先在「隧道」标签关闭。code 固定为 409，前端据此区分业务拦截与容器故障。
     */
    private void assertTunnelClosed(App app, String action) {
        if (app != null && Boolean.TRUE.equals(app.getTunnelEnabled())) {
            throw new BusinessException(TUNNEL_STILL_OPEN_CODE,
                    "该应用已开启隧道，请先在「隧道」标签关闭后再" + action);
        }
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

            list.forEach(c -> {
                if (c.getState().equals("running")) {
                    log.info("正在停止容器 {}", c);
                    client.stopContainerCmd(c.getId()).exec();
                }
                log.info("正在删除容器 {}", c);
                client.removeContainerCmd(c.getId()).exec();
            });
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    public App updateConfig(String id, App.AppConfig appConfig) {
        App app = appRepository.findById(id).orElse(null);
        Assert.notNull(app, "应用不存在");
        // 端口 / 网络模式变化会改变隧道转发的目标地址
        assertTunnelClosed(app, "修改容器配置");
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
     * 应用记录（id、名称、容器配置、镜像、隧道等）保持不变。
     */
    public App migrateApp(String appId, String hostId) {
        App app = requireApp(appId);
        Host host = requireHost(hostId);
        Assert.isTrue(!host.getId().equals(app.getHost().getId()), "目标主机与当前主机相同，无需迁移");
        // 隧道目标是应用所在主机的 IP，迁移后必须重新下发，因此要求先关闭隧道
        assertTunnelClosed(app, "迁移");

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
                "updateUser", "updateTime", "logUrl", "config");
        newApp.setName(newName);
        newApp.setHost(host);
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
