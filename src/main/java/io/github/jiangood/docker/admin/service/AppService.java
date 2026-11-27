package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.Mount;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import io.github.jiangood.docker.admin.BuildSuccessEvent;
import io.github.jiangood.docker.admin.config.DockerSettings;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.DeployLogRepository;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.admin.dto.ConvertToAppVo;
import io.github.jiangood.docker.admin.dto.ImageConfigMetaVo;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.DeployLog;
import io.github.jiangood.docker.admin.entity.DeployTrigger;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.task.entity.SysTaskLog;
import io.github.jiangood.openadmin.modules.logviewer.util.TaskLog;
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
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppService extends BaseService<App> {

    private static final Logger fileLog = TaskLog.logger();

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

    /** 「转为应用」读取容器 inspect 的兜底超时 */
    private static final Duration CONTAINER_INSPECT_TIMEOUT = Duration.ofSeconds(30);

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

    @Resource
    SysTaskLogService taskLogService;

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

    // ---------------- 容器转为应用 ----------------

    /**
     * 按容器 inspect 结果生成「转为应用」的预填应用。
     * <p>
     * 只读容器，不改动任何运行状态；端口 / 目录 / 环境变量 / 标签 / 设备请求等尽量还原，
     * 无法还原的配置以 warnings 返回给前端提示。
     */
    public ConvertToAppVo draftFromContainer(Host host, String containerId) {
        Assert.notNull(host, "主机不存在");
        Assert.hasText(containerId, "containerId 不能为空");

        DockerClient client = dockerManager.getClient(host);
        try {
            InspectContainerResponse inspect = dockerManager.callWithTimeout(CONTAINER_INSPECT_TIMEOUT,
                    "读取容器 " + containerId,
                    () -> client.inspectContainerCmd(containerId).exec());
            return buildDraft(host, inspect, client);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 把一个已经存在的容器登记为平台应用（不重启容器）。
     * <p>
     * 只接受用户可编辑的字段（名称 / 镜像 / 组织 / 备注 / 容器配置），主机与容器 id
     * 一律以服务端参数为准，避免前端伪造其它主机或容器。新应用通过
     * {@link App#getAdoptedContainerId()} 关联当前容器，下一次部署成功后自动清空。
     */
    @Transactional
    public App convertToApp(App input, Host host, String containerId) {
        Assert.notNull(input, "应用信息不能为空");
        Assert.notNull(host, "主机不存在");
        Assert.hasText(containerId, "containerId 不能为空");

        DockerClient client = dockerManager.getClient(host);
        InspectContainerResponse inspect;
        try {
            inspect = dockerManager.callWithTimeout(CONTAINER_INSPECT_TIMEOUT,
                    "读取容器 " + containerId,
                    () -> client.inspectContainerCmd(containerId).exec());
        } finally {
            IOUtils.closeQuietly(client);
        }

        String containerName = firstNameFromInspect(inspect.getName());
        String name = StrUtil.trim(input.getName());
        if (StrUtil.isBlank(name)) {
            name = deriveAppName(containerName, input.getImageUrl());
        }
        Assert.isTrue(StrUtil.isNotBlank(name) && Character.isLetter(name.charAt(0)),
                "应用名称必须以字母开头");
        Assert.isTrue(!appRepository.existsByName(name), "应用名称已存在：" + name);

        ContainerConfig cfg = inspect.getConfig();
        String managedName = cfg == null || cfg.getLabels() == null ? null : cfg.getLabels().get("app.name");
        if (StrUtil.isNotBlank(managedName) && appRepository.existsByName(managedName)) {
            throw new BusinessException("该容器已由应用「" + managedName + "」管理，无需重复转换");
        }
        if (StrUtil.isNotBlank(containerName) && !containerName.equals(name)
                && appRepository.existsByName(containerName)) {
            throw new BusinessException("容器名「" + containerName
                    + "」已被其它应用占用，转换为其它名称会导致两个应用管理同一容器，请先处理该应用");
        }

        App app = new App();
        app.setName(name);
        app.setHost(host);
        app.setImageUrl(StrUtil.trimToNull(input.getImageUrl()));
        Assert.hasText(app.getImageUrl(), "镜像地址不能为空");
        app.setImageTag(StrUtil.blankToDefault(StrUtil.trimToNull(input.getImageTag()), LATEST_TAG));
        app.setSysOrg(input.getSysOrg());
        app.setRemark(input.getRemark());
        App.AppConfig config = input.getConfig() == null ? new App.AppConfig() : input.getConfig();
        normalizeConfig(config);
        app.setConfig(config);
        // 先按容器 id 关联：旧容器没有 app.name 标签、名称也可能被用户改过
        app.setAdoptedContainerId(inspect.getId());

        return saveApp(app, null);
    }

    private ConvertToAppVo buildDraft(Host host, InspectContainerResponse inspect, DockerClient client) {
        ConvertToAppVo vo = new ConvertToAppVo();
        vo.setContainerId(inspect.getId());
        String containerName = firstNameFromInspect(inspect.getName());
        vo.setContainerName(containerName);
        if (inspect.getState() != null) {
            vo.setContainerState(inspect.getState().getStatus());
        }

        List<String> warnings = vo.getWarnings();
        ContainerConfig cfg = inspect.getConfig();
        HostConfig hc = inspect.getHostConfig();

        String imageRef = resolveImageRef(client, inspect, cfg, warnings);
        String imageUrl = "";
        String imageTag = LATEST_TAG;
        if (StrUtil.isNotBlank(imageRef)) {
            if (imageRef.contains("@")) {
                imageUrl = StrUtil.subBefore(imageRef, "@", false);
                warnings.add("容器使用 digest 固定镜像，应用只保存 tag，已按 latest 处理");
            } else if (!imageRef.startsWith("sha256:")) {
                imageUrl = ImageUrlUtils.repoOf(imageRef);
                imageTag = StrUtil.blankToDefault(ImageUrlUtils.tagOf(imageRef), LATEST_TAG);
            }
        }

        App app = new App();
        app.setHost(host);
        app.setName(deriveAppName(containerName, imageUrl));
        app.setImageUrl(imageUrl);
        app.setImageTag(imageTag);

        App.AppConfig config = new App.AppConfig();
        if (hc != null) {
            config.setNetworkMode(StrUtil.blankToDefault(hc.getNetworkMode(), "bridge"));
            // extraHosts 在应用里是空格分隔的一行文本
            if (hc.getExtraHosts() != null && hc.getExtraHosts().length > 0) {
                config.setExtraHosts(String.join(" ", hc.getExtraHosts()));
            }
            String networkMode = hc.getNetworkMode();
            if (networkMode != null && networkMode.startsWith("container:")) {
                warnings.add("容器共享了其他容器的网络命名空间（" + networkMode + "），重新部署后需确保目标容器仍存在");
            } else if (StrUtil.isNotBlank(networkMode)
                    && !"bridge".equals(networkMode) && !"host".equals(networkMode)
                    && !"none".equals(networkMode) && !"default".equals(networkMode)) {
                warnings.add("容器使用自定义网络「" + networkMode
                        + "」，应用不会创建网络，重新部署前需确保该网络仍然存在");
            }
            RestartPolicy rp = hc.getRestartPolicy();
            if (rp != null && StrUtil.isNotBlank(rp.getName()) && !"no".equals(rp.getName())) {
                String policy = rp.getName();
                if (rp.getMaximumRetryCount() != null && rp.getMaximumRetryCount() > 0) {
                    policy = policy + ":" + rp.getMaximumRetryCount();
                }
                if (!"on-failure:5".equals(policy)) {
                    warnings.add("容器重启策略为 " + policy + "，平台应用统一使用 on-failure:5，重新部署后会改为该值");
                }
            }
            if (hc.getDeviceRequests() != null && !hc.getDeviceRequests().isEmpty()) {
                for (DeviceRequest dr : hc.getDeviceRequests()) {
                    App.DeviceRequest item = new App.DeviceRequest();
                    item.setDriver(dr.getDriver());
                    item.setCount(dr.getCount());
                    item.setCapabilities(dr.getCapabilities());
                    config.getDeviceRequests().add(item);
                }
            }
        }
        config.setPorts(portsOf(inspect));
        config.setBinds(bindsOf(inspect, warnings));
        config.setEnvs(envsOf(cfg));
        config.setLabels(labelsOf(cfg, warnings));

        if (cfg != null && cfg.getLabels() != null
                && cfg.getLabels().containsKey(HostAgentService.LABEL_AGENT)) {
            warnings.add("该容器是「主机 Agent」辅助容器，转为应用后可能与 Agent 的部署 / 更新功能冲突");
        }

        if (cfg != null) {
            String[] cmd = cfg.getCmd();
            if (cmd != null && cmd.length > 0) {
                boolean spaced = Arrays.stream(cmd).anyMatch(s -> s != null && s.contains(" "));
                if (spaced) {
                    warnings.add("启动命令包含带空格的参数，应用按空格拆分保存，重新部署后可能改变语义");
                }
                config.setCmd(String.join(" ", cmd));
            }
            if (cfg.getEntrypoint() != null && cfg.getEntrypoint().length > 0) {
                warnings.add("容器使用自定义 entrypoint（" + String.join(" ", cfg.getEntrypoint())
                        + "），应用配置不支持 entrypoint，重新部署时以镜像默认值为准");
            }

            List<String> unsupported = new ArrayList<>();
            if (StrUtil.isNotBlank(cfg.getHostName())) {
                unsupported.add("hostname=" + cfg.getHostName());
            }
            if (StrUtil.isNotBlank(cfg.getUser())) {
                unsupported.add("user=" + cfg.getUser());
            }
            if (StrUtil.isNotBlank(cfg.getWorkingDir())) {
                unsupported.add("workingDir=" + cfg.getWorkingDir());
            }
            if (!unsupported.isEmpty()) {
                warnings.add("以下配置不在应用配置中，重新部署时不会保留：" + String.join("，", unsupported));
            }
        }
        app.setConfig(config);
        vo.setApp(app);

        String managedName = cfg == null || cfg.getLabels() == null ? null : cfg.getLabels().get("app.name");
        if (StrUtil.isNotBlank(managedName) && appRepository.existsByName(managedName)) {
            vo.setManagedAppName(managedName);
            warnings.add("该容器已由应用「" + managedName + "」管理，无需重复转换");
        } else {
            if (StrUtil.isNotBlank(managedName)) {
                warnings.add("容器带有平台标签 app.name=" + managedName
                        + "，但应用记录已不存在，转换后可直接恢复管理");
            }
            if (StrUtil.isNotBlank(containerName) && !containerName.equals(app.getName())
                    && appRepository.existsByName(containerName)) {
                // 容器名与应用名不同时，若已有同名应用，两个应用会同时按名称命中这个容器
                vo.setManagedAppName(containerName);
                warnings.add("已存在与容器同名的应用「" + containerName
                        + "」，为避免两个应用管理同一容器，请先处理该应用");
            } else {
                if (StrUtil.isNotBlank(app.getName()) && appRepository.existsByName(app.getName())) {
                    warnings.add("应用名称「" + app.getName() + "」已被占用，请修改名称");
                }
                if (StrUtil.isNotBlank(containerName) && !containerName.equals(app.getName())) {
                    warnings.add("容器名不符合应用名规则（需字母开头），已自动改为「" + app.getName()
                            + "」，平台仍按容器 ID 接管");
                }
            }
        }

        return vo;
    }

    /**
     * 解析容器使用的镜像引用：优先 config.image，容器按镜像 ID 启动时从本地镜像的 RepoTags 兜底。
     */
    private String resolveImageRef(DockerClient client, InspectContainerResponse inspect,
                                   ContainerConfig cfg, List<String> warnings) {
        String ref = cfg == null ? null : StrUtil.trimToNull(cfg.getImage());
        if (StrUtil.isNotBlank(ref) && !ref.startsWith("sha256:")) {
            return ref;
        }
        try {
            var image = client.inspectImageCmd(inspect.getImageId()).exec();
            if (image.getRepoTags() != null) {
                for (String tag : image.getRepoTags()) {
                    if (StrUtil.isNotBlank(tag) && !"<none>:<none>".equals(tag)) {
                        return tag;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取容器 {} 的镜像信息失败: {}", inspect.getId(), e.getMessage());
        }
        warnings.add("无法确定容器镜像的仓库与版本（可能是本地构建的镜像），请手动填写镜像地址与版本");
        return ref;
    }

    /** 容器名称去掉 inspect 返回值的前导斜杠。 */
    private static String firstNameFromInspect(String name) {
        if (name == null) {
            return "";
        }
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /**
     * 由容器名推导应用名：平台要求字母开头，容器名以数字等开头时补 {@code app-} 前缀；
     * 容器名没有可用字符时退回镜像名，仍为空则用 app。
     */
    private static String deriveAppName(String containerName, String imageUrl) {
        String name = StrUtil.trim(StrUtil.nullToEmpty(containerName));
        if (StrUtil.isNotBlank(name)) {
            return Character.isLetter(name.charAt(0)) ? name : "app-" + name;
        }
        String last = StrUtil.nullToEmpty(imageUrl).split("@")[0];
        last = last.substring(last.lastIndexOf('/') + 1);
        int colon = last.lastIndexOf(':');
        if (colon >= 0) {
            last = last.substring(0, colon);
        }
        last = StrUtil.trim(last);
        if (StrUtil.isBlank(last)) {
            return "app";
        }
        return Character.isLetter(last.charAt(0)) ? last : "app-" + last;
    }

    /**
     * 端口映射：以 HostConfig.portBindings 为准，未绑定的声明端口也保留（重建时会继续暴露）。
     */
    private static List<App.PortBinding> portsOf(InspectContainerResponse inspect) {
        Map<String, App.PortBinding> ports = new LinkedHashMap<>();

        HostConfig hc = inspect.getHostConfig();
        if (hc != null && hc.getPortBindings() != null && hc.getPortBindings().getBindings() != null) {
            for (Map.Entry<ExposedPort, Ports.Binding[]> entry : hc.getPortBindings().getBindings().entrySet()) {
                ExposedPort ep = entry.getKey();
                Ports.Binding[] bindings = entry.getValue();
                if (bindings == null || bindings.length == 0) {
                    portBinding(ports, ep, null);
                } else {
                    for (Ports.Binding binding : bindings) {
                        portBinding(ports, ep, parsePort(binding.getHostPortSpec()));
                    }
                }
            }
        }
        ContainerConfig cfg = inspect.getConfig();
        if (cfg != null && cfg.getExposedPorts() != null) {
            for (ExposedPort ep : cfg.getExposedPorts()) {
                portBinding(ports, ep, null);
            }
        }
        return new ArrayList<>(ports.values());
    }

    private static void portBinding(Map<String, App.PortBinding> ports, ExposedPort ep, Integer publicPort) {
        if (ep == null) {
            return;
        }
        String protocol = ep.getProtocol() == null ? "tcp" : ep.getProtocol().toString();
        String key = ep.getPort() + "/" + protocol;
        App.PortBinding binding = ports.computeIfAbsent(key, k -> {
            App.PortBinding item = new App.PortBinding();
            item.setPrivatePort(ep.getPort());
            item.setProtocol(protocol);
            return item;
        });
        if (publicPort != null) {
            binding.setPublicPort(publicPort);
        }
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

    /**
     * 目录挂载：只保留 bind 类型；命名卷无法用应用的文件映射表达，跳过并提示。
     */
    private static List<App.BindConfig> bindsOf(InspectContainerResponse inspect, List<String> warnings) {
        List<App.BindConfig> binds = new ArrayList<>();
        List<String> namedVolumes = new ArrayList<>();
        if (inspect.getMounts() == null) {
            return binds;
        }
        for (Mount mount : inspect.getMounts()) {
            String destination = mount.getDestination() == null ? null : mount.getDestination().getPath();
            if (StrUtil.isBlank(destination)) {
                continue;
            }
            if (StrUtil.isNotBlank(mount.getName())) {
                namedVolumes.add(mount.getName() + " → " + destination);
                continue;
            }
            if (StrUtil.isBlank(mount.getSource())) {
                continue;
            }
            App.BindConfig bind = new App.BindConfig();
            bind.setPublicVolume(mount.getSource());
            bind.setPrivateVolume(destination);
            bind.setReadOnly(mount.getRW() == null ? null : !mount.getRW());
            binds.add(bind);
        }
        if (!namedVolumes.isEmpty()) {
            warnings.add("容器使用了命名卷（" + String.join("，", namedVolumes)
                    + "），应用配置只支持主机目录挂载，重新部署后不会复用这些卷");
        }
        return binds;
    }

    private static List<App.EnvVar> envsOf(ContainerConfig cfg) {
        List<App.EnvVar> envs = new ArrayList<>();
        if (cfg == null || cfg.getEnv() == null) {
            return envs;
        }
        for (String item : cfg.getEnv()) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            App.EnvVar env = new App.EnvVar();
            env.setName(item.substring(0, eq));
            env.setValue(item.substring(eq + 1));
            envs.add(env);
        }
        return envs;
    }

    /**
     * 容器标签：跳过平台编排标签与 Compose 自身标签，其余按用户标签导入。
     */
    private static List<App.Label> labelsOf(ContainerConfig cfg, List<String> warnings) {
        List<App.Label> labels = new ArrayList<>();
        if (cfg == null || cfg.getLabels() == null) {
            return labels;
        }
        boolean compose = false;
        for (Map.Entry<String, String> entry : cfg.getLabels().entrySet()) {
            String key = entry.getKey();
            if (StrUtil.isBlank(key) || "app.name".equals(key)) {
                continue;
            }
            if (key.startsWith("com.docker.compose.")) {
                compose = true;
                continue;
            }
            App.Label label = new App.Label();
            label.setName(key);
            label.setValue(entry.getValue());
            labels.add(label);
        }
        if (compose) {
            warnings.add("容器由 Docker Compose 创建，Compose 自身标签不会导入；重新部署后该容器将不再属于原 Compose 项目");
        }
        return labels;
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
        String error = null;
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

            // 登记通用任务记录（logId 即部署记录 id），任务中心可统一查看与回放日志
            taskLogService.start(deployLog.getId(), SysTaskLog.TYPE_DEPLOY,
                    deployLog.getAppName() + " · 部署", "app:view", taskLogService.currentTriggerUser());

            TaskLog.bind("task/" + deployLog.getId());
            // 登记为运行中的任务：前端「部署日志」用 WebSocket 实时 tail 本文件，
            // 未登记时 WS 会立刻判定任务已结束并断开（与构建一致）。
            taskLogRegistry.start(deployLog.getId());
            fileLog.info("部署阶段开始");
            Host host = app.getHost();

            // 镜像：tag 为空时按 latest 处理
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
                // 回调的 key 必须是本次部署的日志 key：DefaultCallback 在 docker 流线程上
                // 重新绑定 MDC，传裸 id 会把拉取进度写到 task/ 之外的 {id}.log，部署日志里看不到。
                client.pullImageCmd(image)
                        .exec(new DefaultCallback<PullResponseItem>("task/" + deployLog.getId()))
                        .awaitCompletion();
                fileLog.info("拉取完成 {}", image);
            }


            fileLog.info("开始部署镜像 {}", image);
            App.AppConfig cfg = app.getConfig();

            // 端口 / 卷完全以应用配置为准：镜像声明只在新建应用时作为默认值，用户可以自由增删改
            List<String> portSpecs = portsFromConfig(cfg);
            List<String> volumePaths = volumesFromConfig(cfg);


            List<Container> containers = getContainer(app, client);


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
            error = e.getMessage();
            fileLog.error("部署失败", e);
        } finally {
            // 先收尾数据库记录，再用内层 try/finally 保证「部署中」标记无论记录成败都会清掉。
            // 之前记录保存失败会在 finally 里再次抛出，后面的 remove 永远执行不到，
            // 应用会一直卡在「部署中」。
            try {
                // 部署成功说明新容器已经写入平台标签，接管容器 id 不再需要
                if (success) {
                    try {
                        App latest = appRepository.findById(appId).orElse(null);
                        if (latest != null && StrUtil.isNotBlank(latest.getAdoptedContainerId())) {
                            latest.setAdoptedContainerId(null);
                            appRepository.save(latest);
                        }
                    } catch (Exception e) {
                        log.warn("清理应用 {} 的接管容器 id 失败", appId, e);
                    }
                }
                if (logSaved) {
                    deployLog.setSuccess(success);
                    deployLog.setCompleteTime(LocalDateTime.now());
                    deployLogRepository.save(deployLog);
                    // 先写任务记录再标记结束：日志 WS 断开后前端刷新任务列表即可拿到终态
                    taskLogService.finish(deployLog.getId(), success, error);
                    // 标记任务结束：前端 WS 收到后补发剩余内容并断开
                    taskLogRegistry.finish(deployLog.getId());
                }
            } catch (Exception e) {
                log.error("记录部署结果失败，应用 {} 的这次部署没有写入部署记录", appId, e);
            } finally {
                IOUtils.closeQuietly(client);
                deployingList.remove(appId);
                TaskLog.unbind();
            }
        }

    }

    /**
     * 某应用的部署记录，最新在前，并标记各自是否已有独立日志文件、耗时。
     * 独立日志写在 {@code task/{id}.log}，与框架 TaskLog 的 key 保持一致。
     */
    public List<DeployLog> listDeployLogs(String appId) {
        List<DeployLog> list = deployLogRepository.findByAppIdOrderByCreateTimeDesc(appId);
        File logDir = new File(fileLogConfig.getLogPath(), "task");
        for (DeployLog log : list) {
            log.setLogExists(new File(logDir, log.getId() + ".log").exists());
            if (log.getCreateTime() != null && log.getCompleteTime() != null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), log.getCompleteTime()).toMillis());
            }
        }
        return list;
    }

    // ---------------- 部署状态 ----------------

    public boolean isDeploying(String appId) {
        return appId != null && deployingList.contains(appId);
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
            List<Container> list = getContainer(app, client);
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
            List<Container> list = getContainer(app, client);

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

    /**
     * 应用对应的容器。
     * <p>
     * 正常应用按平台标签 {@code app.name} 定位；「转为应用」接管的旧容器没有该标签，
     * 因此再按容器名兜底，最后按接管时记录的容器 id 精确定位。
     */
    private List<Container> getContainer(App app, DockerClient client) {
        String name = app.getName();
        String adoptedId = app.getAdoptedContainerId();
        if (StrUtil.isNotBlank(adoptedId)) {
            List<Container> adopted = client.listContainersCmd()
                    .withIdFilter(List.of(adoptedId))
                    .withShowAll(true).exec();
            if (!adopted.isEmpty()) {
                return adopted;
            }
            // 接管的容器已不在（可能刚被重新部署替换），继续按标签 / 名称查找
        }

        Map<String, String> labels = dockerManager.getAppLabelFilter(name);
        List<Container> byLabel = client.listContainersCmd()
                .withLabelFilter(labels)
                .withShowAll(true).exec();
        if (!byLabel.isEmpty()) {
            return byLabel;
        }

        // 兼容没有平台标签的同名容器（转为应用时、标签写入之前）
        List<Container> all = client.listContainersCmd().withShowAll(true).exec();
        List<Container> matched = new ArrayList<>();
        for (Container c : all) {
            if (name.equals(firstName(c.getNames()))) {
                matched.add(c);
            }
        }
        return matched;
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        String name = names[0];
        return name != null && name.startsWith("/") ? name.substring(1) : name;
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
        try {
            List<Container> list = getContainer(app, client);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            throw new BusinessException("查询容器状态失败", e);
        } finally {
            IOUtils.closeQuietly(client);
        }
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
            List<Container> list = getContainer(app, client);
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
                "updateUser", "updateTime", "logUrl", "config", "adoptedContainerId");
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
