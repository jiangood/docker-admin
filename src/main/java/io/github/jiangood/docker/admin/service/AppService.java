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
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppService extends BaseService<App> {

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
        deploy(app, false);
    }

    /**
     * 部署应用。
     *
     * @param forcePull 是否强制从仓库拉取镜像；为 false 时若本地已存在同名镜像则跳过拉取，直接使用本地镜像
     */
    @Async
    public void deploy(App app, boolean forcePull) {
        Assert.notNull(app, "应用不存在");
        deployingList.add(app.getId());
        DockerClient client = null;
        DeployLog deployLog = null;
        boolean success = false;
        try {
            MDC.put("logFileId", app.getId());

            // 修改更新时间
            app.setUpdateTime(LocalDateTime.now());
            appRepository.save(app);
            app = appRepository.findById(app.getId()).orElse(null); // 确保关联对象都取出来

            deployLog = new DeployLog();
            deployLog.setAppId(app.getId());
            deployLog.setAppName(app.getName());

            deployLog = deployLogRepository.save(deployLog);
            log.info("部署阶段开始");
            Host host = app.getHost();

            // 镜像
            String image = app.getImageUrl() + ":" + app.getImageTag();

            client = getClient(host, app.getImageUrl());


            if (!forcePull && imageExists(client, image)) {
                log.info("本地已存在镜像 {}，跳过拉取（如需更新请勾选强制拉取）", image);
            } else {
                log.info("开始拉取镜像 {}", image);
                client.pullImageCmd(image).exec(new DefaultCallback<>(app.getId())).awaitCompletion();
            }


            log.info("开始部署镜像 {}", image);
            App.AppConfig cfg = app.getConfig();

            // 仅当镜像来自镜像表（平台构建/已知）且声明非空时，对应维度才严格限制
            ImageTag imageTag = imageTagService.find(app.getImageUrl(), app.getImageTag()).orElse(null);
            boolean strictPorts = strictPorts(imageTag);
            boolean strictVolumes = strictVolumes(imageTag);
            if (strictPorts || strictVolumes) {
                validateConfig(cfg, imageTag);
            }
            // 严格时按镜像声明构造，否则按用户配置构造
            List<String> declaredPorts = strictPorts ? imageTag.getExposedPorts() : portsFromConfig(cfg);
            List<String> declaredVolumes = strictVolumes ? imageTag.getVolumes() : volumesFromConfig(cfg);


            List<Container> containers = getContainer(app.getName(), client);


            for (Container container : containers) {
                log.info("容器状态 {}", container.getState());
                if (container.getState().equals("running")) {
                    log.info("停止容器{}", container.getNames());
                    client.stopContainerCmd(container.getId()).exec();
                }
                log.info("删除容器{}", container.getNames());
                client.removeContainerCmd(container.getId()).exec();
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

                    for (String declared : declaredPorts) {
                        ExposedPort e = ExposedPort.parse(declared);
                        exposedPorts.add(e);

                        App.PortBinding binding = findPortBinding(cfg, e);
                        if (binding != null && binding.getPublicPort() != null) {
                            ports.bind(e, Ports.Binding.bindPort(binding.getPublicPort()));
                        }
                    }

                    hostConfig.withPortBindings(ports);
                }

            }


            // 文件路径绑定：只绑定镜像声明的卷
            List<Bind> binds = new ArrayList<>();

            for (String declared : declaredVolumes) {
                App.BindConfig bindConfig = findBindConfig(cfg, declared);
                if (bindConfig == null || StrUtil.isBlank(bindConfig.getPublicVolume())) {
                    continue;
                }
                // /host:/container:ro
                AccessMode accessMode = Boolean.TRUE.equals(bindConfig.getReadOnly()) ? AccessMode.ro : AccessMode.rw;
                binds.add(new Bind(bindConfig.getPublicVolume(), new Volume(declared), accessMode));
            }
            hostConfig.withBinds(binds);


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
                log.info("设备请求 {}", deviceRequests);
            }


            // hosts，ip域名映射, 支持两种格式， 1. ip 域名 2.域名:ip
            String hosts = cfg.getExtraHosts();
            if (StrUtil.isNotBlank(hosts)) {
                List<String> list = StrUtil.splitTrim(hosts, " ");
                log.info("hosts={}", list);
                hostConfig.withExtraHosts(list.toArray(new String[list.size()]));
            }


            // cpu，限制

            // 日志限制
            LogConfig logConfig = new LogConfig(LogConfig.LoggingType.DEFAULT, new HashMap<>());
            logConfig.getConfig().put("max-size", "200m");
            hostConfig.withLogConfig(logConfig);


            log.info("主机配置{}", hostConfig.getBinds());
            CreateContainerCmd containerCmd = client.createContainerCmd(image);
            containerCmd
                    .withName(app.getName())
                    .withLabels(dockerManager.getAppLabelFilter(app.getName()))
                    .withHostConfig(hostConfig)
                    .withExposedPorts(exposedPorts) // 使用镜像声明的端口
                    .withEnv(envs);

            String cmd = app.getConfig().getCmd();
            if (StrUtil.isNotEmpty(cmd)) {
                List<String> cmds = StrUtil.splitTrim(cmd, " ");
                containerCmd.withCmd(cmds);
            }


            CreateContainerResponse response = containerCmd.exec();


            log.info("创建容器{}", response);

            String containerId = response.getId();

            client.startContainerCmd(containerId).exec();


            log.info("启动容器");
            log.info("部署阶段结束");
            success = true;
        } catch (Exception e) {
            log.info("--------------------------------------------------");
            log.info("部署失败:" + e.getClass().getName() + "=>" + e.getMessage());
            log.info("--------------------------------------------------", e);
        } finally {
            if (deployLog != null) {
                deployLog.setSuccess(success);
                deployLog.setCompleteTime(LocalDateTime.now());
                deployLogRepository.save(deployLog);
            }
            IOUtils.closeQuietly(client);
            deployingList.remove(app.getId());
            MDC.remove("logFileId");
        }

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
            log.warn("查询本地镜像失败，将执行拉取: {}", image, e);
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
        this.deleteContainer(app);
        app.setName(newName);
        App saved = appRepository.save(app);

        this.deploy(app);

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
        tunnelService.removeAppTunnel(app);
        deleteContainer(app);

        appRepository.deleteById(id);
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
        normalizeConfig(appConfig);
        ImageTag iv = imageTagService.find(app.getImageUrl(), app.getImageTag()).orElse(null);
        if (iv != null) {
            validateConfig(appConfig, iv);
        }
        app.setConfig(appConfig);

        app = appRepository.save(app);
        return app;
    }

    /**
     * 容器配置元数据：镜像表声明的端口/卷 + 已保存的主机侧映射。
     * 镜像来自镜像表且声明非空时对应维度为严格模式（容器侧只读、不可增删），否则可自由编辑。
     */
    public ImageConfigMetaVo getConfigMeta(App app) {
        ImageConfigMetaVo vo = new ImageConfigMetaVo();
        vo.setImageUrl(app.getImageUrl());

        App.AppConfig cfg = app.getConfig();
        ImageTag iv = imageTagService.find(app.getImageUrl(), app.getImageTag()).orElse(null);
        boolean strictPorts = strictPorts(iv);
        boolean strictVolumes = strictVolumes(iv);
        vo.setStrictPorts(strictPorts);
        vo.setStrictVolumes(strictVolumes);

        if (strictPorts) {
            for (String declared : iv.getExposedPorts()) {
                ExposedPort ep = ExposedPort.parse(declared);
                ImageConfigMetaVo.PortMeta pm = new ImageConfigMetaVo.PortMeta();
                pm.setPrivatePort(ep.getPort());
                pm.setProtocol(ep.getProtocol().toString().toUpperCase());
                App.PortBinding saved = cfg == null ? null : findPortBinding(cfg, ep);
                if (saved != null) {
                    pm.setPublicPort(saved.getPublicPort());
                }
                vo.getPorts().add(pm);
            }
        } else if (cfg != null && cfg.getPorts() != null) {
            for (App.PortBinding p : cfg.getPorts()) {
                if (p == null || p.getPrivatePort() == null) {
                    continue;
                }
                ImageConfigMetaVo.PortMeta pm = new ImageConfigMetaVo.PortMeta();
                pm.setPrivatePort(p.getPrivatePort());
                pm.setProtocol(StrUtil.isBlank(p.getProtocol()) ? "TCP" : p.getProtocol().toUpperCase());
                pm.setPublicPort(p.getPublicPort());
                vo.getPorts().add(pm);
            }
        }

        if (strictVolumes) {
            for (String declared : iv.getVolumes()) {
                ImageConfigMetaVo.VolumeMeta vm = new ImageConfigMetaVo.VolumeMeta();
                vm.setPrivateVolume(declared);
                App.BindConfig saved = cfg == null ? null : findBindConfig(cfg, declared);
                if (saved != null) {
                    vm.setPublicVolume(saved.getPublicVolume());
                    vm.setReadOnly(saved.getReadOnly());
                }
                vo.getVolumes().add(vm);
            }
        } else if (cfg != null && cfg.getBinds() != null) {
            for (App.BindConfig b : cfg.getBinds()) {
                if (b == null || StrUtil.isBlank(b.getPrivateVolume())) {
                    continue;
                }
                ImageConfigMetaVo.VolumeMeta vm = new ImageConfigMetaVo.VolumeMeta();
                vm.setPrivateVolume(b.getPrivateVolume());
                vm.setPublicVolume(b.getPublicVolume());
                vm.setReadOnly(b.getReadOnly());
                vo.getVolumes().add(vm);
            }
        }

        return vo;
    }

    /**
     * 创建主机 docker 客户端。
     * <p>
     * 仅当镜像来自注册中心（镜像 host 与注册中心地址一致）时才附带注册中心凭据。
     * docker-java 会把客户端级凭据（registry.url/username/password）用于所有 pull，
     * 拉取 ghcr.io / Docker Hub 等公共镜像时带上注册中心账号密码会被直接拒绝（401/403）。
     */
    public DockerClient getClient(Host host, String imageUrl) {
        Registry registry = registryService.getEffective();
        if (registry != null && sameRegistry(imageUrl, registry.getUrl())) {
            return dockerManager.getClient(host, registry);
        }
        return dockerManager.getClient(host);
    }

    /**
     * 镜像地址的 registry host 是否等于注册中心地址（两者都归一为不含协议、不带路径的 host）。
     * 镜像首段不含 "." / ":" 且不是 localhost 时视为 Docker Hub，无显式注册中心。
     */
    private static boolean sameRegistry(String imageUrl, String registryUrl) {
        String imageHost = imageHost(imageUrl);
        return imageHost != null && imageHost.equalsIgnoreCase(registryHost(registryUrl));
    }

    private static String imageHost(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        String image = imageUrl.trim();
        int slash = image.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String first = image.substring(0, slash);
        return (first.contains(".") || first.contains(":") || "localhost".equals(first)) ? first : null;
    }

    private static String registryHost(String registryUrl) {
        if (StrUtil.isBlank(registryUrl)) {
            return null;
        }
        String url = registryUrl.trim();
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            url = url.substring(scheme + 3);
        }
        int slash = url.indexOf('/');
        if (slash >= 0) {
            url = url.substring(0, slash);
        }
        return url;
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
     * 是否严格限制端口：镜像来自镜像表且声明了端口。
     */
    private static boolean strictPorts(ImageTag iv) {
        return iv != null && iv.getExposedPorts() != null && !iv.getExposedPorts().isEmpty();
    }

    /**
     * 是否严格限制卷：镜像来自镜像表且声明了卷。
     */
    private static boolean strictVolumes(ImageTag iv) {
        return iv != null && iv.getVolumes() != null && !iv.getVolumes().isEmpty();
    }

    /**
     * 从用户配置推导容器端口（非严格模式的兜底）。
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
     * 从用户配置推导卷路径（非严格模式的兜底）。
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

    /**
     * 校验：严格维度下，配置中的端口/卷必须全部是镜像声明中存在的项。
     */
    private void validateConfig(App.AppConfig cfg, ImageTag iv) {
        if (cfg == null || iv == null) {
            return;
        }
        if (strictPorts(iv)) {
            List<String> declaredPorts = iv.getExposedPorts();
            List<App.PortBinding> ports = cfg.getPorts() == null ? List.of() : cfg.getPorts();
            for (App.PortBinding p : ports) {
                if (p.getPrivatePort() == null) {
                    continue;
                }
                String key = portKey(p.getPrivatePort(), p.getProtocol());
                if (!declaredPorts.contains(key)) {
                    throw new BusinessException("端口 " + key + " 未在镜像中声明" + declaredHint(declaredPorts));
                }
            }
        }

        if (strictVolumes(iv)) {
            List<String> declaredVolumes = iv.getVolumes();
            List<App.BindConfig> binds = cfg.getBinds() == null ? List.of() : cfg.getBinds();
            for (App.BindConfig b : binds) {
                if (StrUtil.isBlank(b.getPrivateVolume())) {
                    continue;
                }
                String path = normalizeVolumePath(b.getPrivateVolume());
                boolean declared = declaredVolumes.stream()
                        .anyMatch(v -> normalizeVolumePath(v).equals(path));
                if (!declared) {
                    throw new BusinessException("卷 " + b.getPrivateVolume() + " 未在镜像中声明" + declaredHint(declaredVolumes));
                }
            }
        }
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

    private static String declaredHint(List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return "（镜像未声明任何端口/卷）";
        }
        return "，镜像声明：" + String.join(", ", declared);
    }

    @EventListener
    public void onBuildSuccess(BuildSuccessEvent event) {
        log.info("构建成功，开始检测关联应用");
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
            $this.deploy(app);
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


    public App copyApp(String appId, String hostId) {
        App app = appRepository.findById(appId).orElse(null);
        Assert.notNull(app, "应用不存在");
        Host host = hostRepository.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");

        App newApp = new App();
        // 不复制 id/name/host 及审计字段
        BeanUtils.copyProperties(app, newApp, "id", "name", "host", "createUser", "createTime",
                "updateUser", "updateTime", "logUrl", "config");
        newApp.setName(buildCopyName(app.getName()));
        newApp.setHost(host);
        if (app.getConfig() != null) {
            newApp.setConfig(JsonTool.jsonToBean(JsonTool.toJsonQuietly(app.getConfig()), App.AppConfig.class));
        }

        return appRepository.save(newApp);
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
