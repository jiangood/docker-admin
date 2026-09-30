package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.RestartPolicy;
import io.github.jiangood.docker.admin.dao.TunnelNodeRepository;
import io.github.jiangood.docker.admin.dao.TunnelSettingRepository;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dto.TunnelContainerVo;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.TunnelNode;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 隧道：部署 nps 服务端、维护节点 npc 客户端、对账域名解析。
 * <p>
 * 与 frp 方案的关键差别：nps 的 npc 支持「无配置文件模式」，隧道与域名解析都在服务端，
 * 因此**不需要动态构建镜像**，改路由只是一次 API 调用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TunnelService extends BaseService<TunnelSetting> {

    public static final String LABEL_MANAGED = "docker-admin.managed";
    public static final String LABEL_ROLE = "docker-admin.role";
    public static final String LABEL_HOST = "docker-admin.host";
    public static final String ROLE_NPS = "nps";
    public static final String ROLE_NPC = "npc";
    public static final String NPS_CONTAINER = "docker-admin-nps";
    public static final String NPC_CONTAINER = "docker-admin-npc";

    private final TunnelSettingRepository settingRepository;
    private final TunnelNodeRepository nodeRepository;

    @Resource
    AppRepository appRepository;

    @Resource
    DockerClientManager dockerManager;

    @Resource
    HostService hostService;

    @Resource
    NpsConfManager confManager;

    @Resource
    NpsApiClient apiClient;

    @Resource
    TaskLogRegistry taskLogRegistry;

    // ------------------------------------------------------------------ 设置

    /**
     * 生效的隧道设置（数据库中的最新一条）。
     */
    public TunnelSetting getSetting() {
        List<TunnelSetting> list = settingRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"));
        return list.isEmpty() ? null : list.get(0);
    }

    public TunnelSetting requireSetting() {
        TunnelSetting s = getSetting();
        Assert.notNull(s, "尚未配置隧道，请先在【隧道】页面选择 nps 主机并保存");
        return s;
    }

    /**
     * 展示用：密钥掩码后的 conf 原文。
     */
    public String confForView() {
        TunnelSetting s = getSetting();
        return s == null ? null : confManager.maskConf(s.getNpsConf());
    }

    /**
     * 按表单字段渲染默认 conf（用于「生成默认配置」按钮），密钥沿用库里的真实值后掩码返回。
     */
    public String previewConf(TunnelSetting input) {
        TunnelSetting old = getSetting();
        if (old != null) {
            if (StrUtil.isBlank(input.getWebPassword())) {
                input.setWebPassword(old.getWebPassword());
            }
            if (StrUtil.isBlank(input.getAuthKey())) {
                input.setAuthKey(old.getAuthKey());
            }
            if (StrUtil.isBlank(input.getAuthCryptKey())) {
                input.setAuthCryptKey(old.getAuthCryptKey());
            }
        }
        confManager.ensureSecrets(input);
        return confManager.maskConf(confManager.defaultConf(input));
    }

    /**
     * 保存设置。
     * <p>
     * 表单字段是默认值来源；conf 原文是持久化真值。留空时按表单生成，填了则解析回填表单字段。
     */
    @Transactional
    public TunnelSetting saveSetting(TunnelSetting input) {
        TunnelSetting old = getSetting();
        if (StrUtil.isBlank(input.getId()) && old != null) {
            input.setId(old.getId());
        }
        if (old != null) {
            if (StrUtil.isBlank(input.getWebPassword()) || NpsConfManager.MASK_SECRET.equals(input.getWebPassword())) {
                input.setWebPassword(old.getWebPassword());
            }
            if (StrUtil.isBlank(input.getAuthKey())) {
                input.setAuthKey(old.getAuthKey());
            }
            if (StrUtil.isBlank(input.getAuthCryptKey())) {
                input.setAuthCryptKey(old.getAuthCryptKey());
            }
        }
        Assert.notNull(input.getHost(), "请选择 nps 主机");
        Assert.notNull(input.getHost().getId(), "请选择 nps 主机");
        confManager.ensureSecrets(input);

        if (StrUtil.isBlank(input.getNpsConf())) {
            input.setNpsConf(confManager.defaultConf(input));
        } else {
            input.setNpsConf(confManager.restoreMasked(input.getNpsConf(), input));
            confManager.syncFromConf(input, input.getNpsConf());
        }
        input.setConfHash(confManager.hash(input.getNpsConf()));

        if (old == null) {
            return create(input);
        }
        return update(input, null);
    }

    // ------------------------------------------------------------------ 部署 nps

    /**
     * 部署 / 重启 nps。异步执行，日志写入 {@code /data/logs/{logId}.log}。
     */
    @Async
    public void deployNps(String logId) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        TunnelSetting s = null;
        try {
            s = requireSetting();
            Assert.isTrue(s.deployable(), "隧道设置不完整：请填写 nps 主机、连接地址与域名后缀");
            Assert.isTrue(Boolean.TRUE.equals(s.getEnabled()), "隧道功能未启用，请先打开总开关");

            Host host = s.getHost();
            log.info("开始部署 nps");
            log.info("主机: {}", host.getName());
            log.info("连接地址: {}:{} (web)", s.getNpsAddr(), s.webPort());
            log.info("监听端口: http={} bridge={} tlsBridge={} web={}",
                    s.httpProxyPort(), s.bridgePort(), s.tlsBridgePort(), s.webPort());
            log.info("镜像: {}", s.npsImage());

            client = client(host);

            checkPortConflicts(client, s);
            removeContainer(client, NPS_CONTAINER);
            ensureVolume(client, s.confVolume());

            log.info("创建容器 {}", NPS_CONTAINER);
            HostConfig hostConfig = new HostConfig()
                    .withNetworkMode("host")
                    .withRestartPolicy(RestartPolicy.alwaysRestart())
                    .withMounts(List.of(new Mount()
                            .withType(MountType.VOLUME)
                            .withSource(s.confVolume())
                            .withTarget(NpsConfManager.CONF_DIR)))
                    .withLogConfig(new LogConfig(LogConfig.LoggingType.DEFAULT, Map.of("max-size", "50m")));

            String containerId = client.createContainerCmd(s.npsImage())
                    .withName(NPS_CONTAINER)
                    .withLabels(labels(ROLE_NPS, host.getId()))
                    .withHostConfig(hostConfig)
                    .exec()
                    .getId();
            log.info("容器已创建: {}", containerId);

            log.info("写入 nps.conf");
            confManager.writeConf(client, containerId, s.getNpsConf());

            log.info("启动容器");
            client.startContainerCmd(containerId).exec();

            if (!waitApiReady(s)) {
                String logs = containerLogs(client, containerId, 40);
                throw new BusinessException("nps 启动后 WebAPI 不可用。容器日志：\n" + logs);
            }
            log.info("nps WebAPI 已就绪: {}", apiClient.baseUrl(s));

            s.setNpsContainerId(containerId);
            s.setNpsStatus("running");
            s.setLastError(null);
            s.setLastDeployTime(LocalDateTime.now());
            save(s);

            log.info("部署阶段结束");
        } catch (Exception e) {
            log.error("部署 nps 失败: {}", e.getMessage(), e);
            if (s != null) {
                s.setNpsStatus("error");
                s.setLastError(StrUtil.maxLength(e.getMessage(), 500));
                save(s);
            }
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 总开关。
     */
    @Async
    public void toggle(boolean enabled, String logId) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        try {
            TunnelSetting s = requireSetting();
            s.setEnabled(enabled);
            save(s);
            log.info(enabled ? "启用隧道" : "关闭隧道");

            if (enabled) {
                deployNps(logId);
                syncAllNodes();
                return;
            }

            // 关闭：移除全部 npc 与 nps 容器，保留数据卷与设置
            List<TunnelNode> nodes = nodeRepository.findAll();
            for (TunnelNode node : nodes) {
                try {
                    client = client(node.getHost());
                    removeContainer(client, NPC_CONTAINER);
                    node.setContainerId(null);
                    node.setStatus("stopped");
                    node.setLastSyncTime(LocalDateTime.now());
                    nodeRepository.save(node);
                    log.info("已移除节点 {} 的 npc 容器", node.getHost().getName());
                } catch (Exception e) {
                    log.warn("移除 npc 容器失败({}): {}", node.getHost() == null ? "?" : node.getHost().getName(), e.getMessage());
                } finally {
                    IOUtils.closeQuietly(client);
                    client = null;
                }
            }

            try {
                client = client(s.getHost());
                removeContainer(client, NPS_CONTAINER);
                log.info("已移除 nps 容器");
            } finally {
                IOUtils.closeQuietly(client);
                client = null;
            }

            s.setNpsContainerId(null);
            s.setNpsStatus("stopped");
            save(s);
            log.info("隧道已停用（数据卷与配置保留）");
        } catch (Exception e) {
            log.error("切换总开关失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    // ------------------------------------------------------------------ 容器管理

    /**
     * 各主机上的 nps / npc 容器（含未被平台记录的残留容器）。
     */
    public List<TunnelContainerVo> listContainers() {
        TunnelSetting s = getSetting();
        String npsId = s == null ? null : s.getNpsContainerId();
        Map<String, String> nodeContainers = new LinkedHashMap<>();
        for (TunnelNode node : nodeRepository.findAll()) {
            if (node.getHost() != null && StrUtil.isNotBlank(node.getContainerId())) {
                nodeContainers.put(node.getContainerId(), node.getHost().getName());
            }
        }

        List<TunnelContainerVo> list = new ArrayList<>();
        for (Host host : hostService.findAll()) {
            DockerClient client = null;
            try {
                client = client(host);
                List<Container> containers = client.listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(LABEL_MANAGED, "true"))
                        .exec();
                for (Container c : containers) {
                    TunnelContainerVo vo = new TunnelContainerVo();
                    vo.setHostId(host.getId());
                    vo.setHostName(host.getName());
                    vo.setContainerId(c.getId());
                    vo.setImage(c.getImage());
                    vo.setState(c.getState());
                    vo.setStatus(c.getStatus());
                    vo.setName(firstName(c));
                    Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();
                    vo.setRole(labels.getOrDefault(LABEL_ROLE, "unknown"));
                    boolean referenced = c.getId().equals(npsId) || nodeContainers.containsKey(c.getId());
                    vo.setReferenced(referenced);
                    vo.setOrphan(!referenced);
                    list.add(vo);
                }
            } catch (Exception e) {
                log.warn("列出主机 {} 的隧道容器失败: {}", host.getName(), e.getMessage());
            } finally {
                IOUtils.closeQuietly(client);
            }
        }
        return list;
    }

    /**
     * 手动清理容器：只停删容器并清平台侧引用，不动数据卷与配置。
     */
    @Async
    public void cleanContainers(List<String> containerIds, String logId) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        int ok = 0;
        int fail = 0;
        try {
            TunnelSetting s = getSetting();
            for (String containerId : containerIds) {
                DockerClient client = null;
                try {
                    Host host = locateHost(containerId, s);
                    Assert.notNull(host, "找不到容器 " + containerId + " 所属主机");
                    client = client(host);
                    log.info("清理容器 {} ({})", StrUtil.maxLength(containerId, 12), host.getName());
                    client.stopContainerCmd(containerId).exec();
                    client.removeContainerCmd(containerId).exec();
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("清理容器 {} 失败: {}", StrUtil.maxLength(containerId, 12), e.getMessage());
                } finally {
                    IOUtils.closeQuietly(client);
                }
            }
            // 清空平台侧引用
            if (s != null && containerIds.contains(s.getNpsContainerId())) {
                s.setNpsContainerId(null);
                s.setNpsStatus("unknown");
                save(s);
            }
            for (TunnelNode node : nodeRepository.findAll()) {
                if (containerIds.contains(node.getContainerId())) {
                    node.setContainerId(null);
                    node.setStatus("unknown");
                    nodeRepository.save(node);
                }
            }
            log.info("清理完成：成功 {} 个，失败 {} 个", ok, fail);
        } finally {
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 连通性检测：后端到 nps 的端口连通性 + WebAPI 可用性 + 节点 npc 日志。
     */
    public Map<String, Object> probe(String hostId) {
        TunnelSetting s = requireSetting();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("npsAddr", s.getNpsAddr());
        result.put("apiReady", apiClient.ping(s));

        List<Map<String, Object>> ports = new ArrayList<>();
        ports.add(tcpProbe(s.getNpsAddr(), s.bridgePort(), TunnelSetting.BRIDGE_PLAIN));
        ports.add(tcpProbe(s.getNpsAddr(), s.tlsBridgePort(), TunnelSetting.BRIDGE_TLS));
        result.put("ports", ports);

        if (StrUtil.isNotBlank(hostId)) {
            TunnelNode node = nodeRepository.findByHost_Id(hostId).orElse(null);
            result.put("nodeStatus", node == null ? null : node.getStatus());
            result.put("npcConnected", node != null && isClientConnected(node, s));
            if (node != null && StrUtil.isNotBlank(node.getContainerId()) && node.getHost() != null) {
                DockerClient client = null;
                try {
                    client = client(node.getHost());
                    result.put("npcLog", containerLogs(client, node.getContainerId(), 20));
                } catch (Exception e) {
                    result.put("npcLog", "读取日志失败：" + e.getMessage());
                } finally {
                    IOUtils.closeQuietly(client);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部工具

    private boolean isClientConnected(TunnelNode node, TunnelSetting s) {
        if (node.getNpsClientId() == null) {
            return false;
        }
        try {
            var rows = apiClient.listClients(s).get("rows");
            if (rows == null) {
                return false;
            }
            for (var row : rows) {
                if (row.get("Id") != null && row.get("Id").asInt() == node.getNpsClientId()) {
                    return row.get("IsConnect") != null && row.get("IsConnect").asBoolean();
                }
            }
        } catch (Exception e) {
            log.debug("查询 nps 客户端状态失败: {}", e.getMessage());
        }
        return false;
    }

    private Map<String, Object> tcpProbe(String host, int port, String mode) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("mode", mode);
        item.put("port", port);
        boolean ok;
        String error = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            ok = true;
        } catch (Exception e) {
            ok = false;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        item.put("reachable", ok);
        item.put("error", error);
        return item;
    }

    private Host locateHost(String containerId, TunnelSetting s) {
        if (s != null && s.getNpsContainerId() != null && s.getNpsContainerId().equals(containerId)) {
            return s.getHost();
        }
        for (TunnelNode node : nodeRepository.findAll()) {
            if (containerId.equals(node.getContainerId())) {
                return node.getHost();
            }
        }
        // 残留容器：逐台主机找
        for (Host host : hostService.findAll()) {
            DockerClient client = null;
            try {
                client = client(host);
                if (!client.listContainersCmd().withShowAll(true).withIdFilter(List.of(containerId)).exec().isEmpty()) {
                    return host;
                }
            } catch (Exception ignored) {
                // 忽略不可达主机
            } finally {
                IOUtils.closeQuietly(client);
            }
        }
        return null;
    }

    private void checkPortConflicts(DockerClient client, TunnelSetting s) {
        List<Integer> ports = List.of(s.httpProxyPort(), s.bridgePort(), s.tlsBridgePort(), s.webPort());
        try {
            for (Container c : client.listContainersCmd().withShowAll(true).exec()) {
                if (NPS_CONTAINER.equals(firstName(c))) {
                    continue;
                }
                String portStr = c.getPorts() == null ? "" : java.util.Arrays.toString(c.getPorts());
                for (Integer p : ports) {
                    if (portStr.contains(":" + p + "->") || portStr.contains(":" + p + "-")) {
                        log.warn("端口 {} 可能已被容器 {} 占用", p, firstName(c));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检查端口占用失败: {}", e.getMessage());
        }
    }

    private boolean waitApiReady(TunnelSetting s) {
        for (int i = 0; i < 30; i++) {
            if (apiClient.ping(s)) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void ensureVolume(DockerClient client, String name) {
        try {
            client.inspectVolumeCmd(name).exec();
            log.info("数据卷已存在: {}", name);
        } catch (Exception e) {
            log.info("创建数据卷: {}", name);
            client.createVolumeCmd().withName(name).withLabels(Map.of(LABEL_MANAGED, "true")).exec();
        }
    }

    private void removeContainer(DockerClient client, String name) {
        try {
            for (Container c : client.listContainersCmd().withShowAll(true).withNameFilter(List.of(name)).exec()) {
                if (!name.equals(firstName(c))) {
                    continue;
                }
                log.info("移除已有容器 {}", name);
                try {
                    client.stopContainerCmd(c.getId()).exec();
                } catch (Exception ignored) {
                    // 已停止
                }
                client.removeContainerCmd(c.getId()).exec();
            }
        } catch (Exception e) {
            log.warn("移除容器 {} 失败: {}", name, e.getMessage());
        }
    }

    private String containerLogs(DockerClient client, String containerId, int tail) {
        StringBuilder sb = new StringBuilder();
        try {
            client.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion();
        } catch (Exception e) {
            sb.append("读取日志失败：").append(e.getMessage());
        }
        return sb.toString();
    }

    private DockerClient client(Host host) {
        return dockerManager.getClient(host);
    }

    private Map<String, String> labels(String role, String hostId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_ROLE, role);
        if (StrUtil.isNotBlank(hostId)) {
            labels.put(LABEL_HOST, hostId);
        }
        return labels;
    }

    private static String firstName(Container c) {
        String[] names = c.getNames();
        if (names == null || names.length == 0) {
            return null;
        }
        return StrUtil.removePrefix(names[0], "/");
    }

    /**
     * 生成一个节点用的 vkey。
     */
    public static String newVkey() {
        return "node-" + RandomUtil.randomString(16);
    }

    // ------------------------------------------------------------------ 应用隧道（P2）

    /**
     * 应用隧道信息：内网访问地址 + 隧道访问地址。
     */
    public App.TunnelInfo tunnelInfo(App app) {
        App.TunnelInfo info = new App.TunnelInfo();
        if (app == null) {
            return info;
        }
        info.setEnabled(Boolean.TRUE.equals(app.getTunnelEnabled()));
        if (!info.getEnabled()) {
            return info;
        }
        info.setSubdomain(effectiveSubdomain(app));
        TunnelSetting s = getSetting();
        if (s == null) {
            info.setError("隧道未配置，请先在【隧道】页面完成设置");
            return info;
        }
        try {
            int port = resolveLocalPort(app);
            info.setPort(port);
            String hostAddr = hostAddress(app.getHost());
            if (StrUtil.isNotBlank(hostAddr)) {
                info.setInnerUrl("http://" + hostAddr + ":" + port);
            }
            String sub = info.getSubdomain();
            if (StrUtil.isBlank(sub)) {
                info.setError("子域名不能为空（应用名称为中文时请手动填写）");
            } else if (StrUtil.isBlank(s.getSubDomainHost())) {
                info.setError("隧道未配置域名后缀");
            } else {
                String url = "http://" + sub + "." + s.getSubDomainHost();
                if (s.httpProxyPort() != 80) {
                    url = url + ":" + s.httpProxyPort();
                }
                info.setTunnelUrl(url);
            }
        } catch (Exception e) {
            info.setError(e.getMessage());
        }
        return info;
    }

    /**
     * 开启 / 关闭某个应用的隧道。
     */
    @Transactional
    public void updateTunnel(App app, boolean enabled, String subdomain, Integer port) {
        if (!enabled) {
            app.setTunnelEnabled(false);
            appRepository.save(app);
            onAppChanged(app);
            return;
        }

        TunnelSetting s = requireSetting();
        Assert.isTrue(Boolean.TRUE.equals(s.getEnabled()),
                "隧道功能未启用，请先在【隧道】页面开启总开关");

        String sub = StrUtil.blankToDefault(slug(subdomain), slug(app.getName()));
        Assert.isTrue(StrUtil.isNotBlank(sub), "子域名不能为空（应用名称为中文时请手动填写）");
        Assert.isTrue(sub.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"),
                "子域名只能包含小写字母、数字和短横线：" + sub);
        assertSubdomainFree(app, sub);

        app.setTunnelSubdomain(sub);
        app.setTunnelPort(port);
        app.setTunnelEnabled(true);
        resolveLocalPort(app); // 端口可用性校验

        appRepository.save(app);
        onAppChanged(app);
    }

    /**
     * 应用发生变化后同步其所在节点（异步、幂等）。
     */
    public void onAppChanged(App app) {
        if (app == null || app.getHost() == null || app.getHost().getId() == null) {
            return;
        }
        TunnelSetting s = getSetting();
        if (s == null || !Boolean.TRUE.equals(s.getEnabled())) {
            return;
        }
        syncNode(app.getHost().getId(), "tunnel-sync-" + RandomUtil.randomString(12));
    }

    /**
     * 节点对账：把该节点上所有开启隧道的应用写进 nps 的域名解析。
     */
    @Async
    public void syncNode(String hostId, String logId) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);
        DockerClient client = null;
        try {
            TunnelSetting s = requireSetting();
            Host host = hostService.findById(hostId).orElse(null);
            if (host == null) {
                log.warn("主机不存在，跳过：{}", hostId);
                return;
            }
            log.info("开始同步节点 {}", host.getName());
            client = client(host);
            doSyncNode(client, host, s);
            log.info("节点 {} 同步完成", host.getName());
        } catch (Exception e) {
            log.error("同步节点失败: {}", e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 启用总开关后把所有节点对齐一次。
     */
    public void syncAllNodes() {
        for (Host host : hostService.findAll()) {
            try {
                TunnelNode node = nodeRepository.findByHost_Id(host.getId()).orElse(null);
                if (tunnelApps(host.getId()).isEmpty() && node == null) {
                    continue;
                }
                syncNode(host.getId(), "tunnel-sync-" + RandomUtil.randomString(12));
            } catch (Exception e) {
                log.warn("同步节点 {} 失败: {}", host.getName(), e.getMessage());
            }
        }
    }

    /**
     * 节点列表（隧道页面展示）。
     */
    public List<Map<String, Object>> listNodes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TunnelNode node : nodeRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"))) {
            if (node.getHost() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("hostId", node.getHost().getId());
            item.put("hostName", node.getHost().getName());
            item.put("npsClientId", node.getNpsClientId());
            item.put("containerId", node.getContainerId());
            item.put("status", node.getStatus());
            item.put("lastError", node.getLastError());
            item.put("lastSyncTime", node.getLastSyncTime());
            item.put("appCount", tunnelApps(node.getHost().getId()).size());
            item.put("connected", isClientConnected(node, getSetting()));
            list.add(item);
        }
        return list;
    }

    // ------------------------------------------------------------------ P2 内部

    private void doSyncNode(DockerClient client, Host host, TunnelSetting s) {
        List<App> apps = tunnelApps(host.getId());
        TunnelNode node = nodeRepository.findByHost_Id(host.getId()).orElse(null);

        if (apps.isEmpty()) {
            if (node == null) {
                log.info("节点 {} 没有隧道应用，无需处理", host.getName());
                return;
            }
            log.info("节点 {} 已无隧道应用，清理客户端与容器", host.getName());
            removeAllRoutes(s, node);
            removeContainer(client, NPC_CONTAINER);
            if (node.getNpsClientId() != null) {
                try {
                    apiClient.deleteClient(s, node.getNpsClientId());
                } catch (Exception e) {
                    log.warn("删除 nps 客户端失败: {}", e.getMessage());
                }
            }
            nodeRepository.delete(node);
            return;
        }

        node = ensureNode(client, host, s);
        reconcileRoutes(client, host, s, node, apps);
    }

    /**
     * 确认节点可用：nps 客户端（vkey）+ npc 容器。
     */
    private TunnelNode ensureNode(DockerClient client, Host host, TunnelSetting s) {
        TunnelNode node = nodeRepository.findByHost_Id(host.getId()).orElse(null);
        if (node == null) {
            node = new TunnelNode();
            node.setHost(host);
            node.setVkey(newVkey());
            node = nodeRepository.save(node);
            log.info("创建隧道节点记录 vkey={}", node.getVkey());
        }
        if (node.getNpsClientId() == null) {
            int clientId = apiClient.addClient(s, node.getVkey(), host.getName());
            node.setNpsClientId(clientId);
            node = nodeRepository.save(node);
            log.info("已在 nps 上创建客户端 id={} remark={}", clientId, host.getName());
        }

        Container existing = findContainer(client, NPC_CONTAINER);
        if (existing == null || !"running".equals(existing.getState())) {
            removeContainer(client, NPC_CONTAINER);
            List<String> cmd = new ArrayList<>();
            cmd.add("-server=" + s.getNpsAddr() + ":" + s.nodeBridgePort());
            cmd.add("-vkey=" + node.getVkey());
            if (s.isBridgeTls()) {
                cmd.add("-tls_enable=true");
            }
            log.info("启动 npc 容器，连接 {}:{}（{}）", s.getNpsAddr(), s.nodeBridgePort(),
                    s.isBridgeTls() ? "TLS" : "明文");
            String cid = client.createContainerCmd(s.npcImage())
                    .withName(NPC_CONTAINER)
                    .withLabels(labels(ROLE_NPC, host.getId()))
                    .withHostConfig(baseHostConfig())
                    .withCmd(cmd)
                    .exec()
                    .getId();
            client.startContainerCmd(cid).exec();
            node.setContainerId(cid);
            log.info("npc 容器已启动 {}", cid.substring(0, 12));
        } else {
            node.setContainerId(existing.getId());
        }

        node.setStatus("running");
        node.setLastError(null);
        node.setLastSyncTime(LocalDateTime.now());
        return nodeRepository.save(node);
    }

    /**
     * 域名解析对账：按应用配置新增 / 修改 / 删除。
     */
    private void reconcileRoutes(DockerClient client, Host host, TunnelSetting s, TunnelNode node, List<App> apps) {
        Map<String, Route> expected = new LinkedHashMap<>();
        for (App app : apps) {
            try {
                Route r = routeOf(app, s);
                expected.put(r.domain(), r);
            } catch (Exception e) {
                log.warn("应用 {} 的隧道配置无效，跳过：{}", app.getName(), e.getMessage());
            }
        }

        Map<String, JsonNode> actual = new LinkedHashMap<>();
        try {
            JsonNode rows = apiClient.listHosts(s, node.getNpsClientId()).path("rows");
            for (JsonNode row : rows) {
                actual.put(row.path("Host").asText(), row);
            }
        } catch (Exception e) {
            throw new BusinessException("读取域名解析失败：" + e.getMessage(), e);
        }

        for (Route r : expected.values()) {
            JsonNode exist = actual.get(r.domain());
            try {
                if (exist == null) {
                    int id = apiClient.addHost(s, node.getNpsClientId(), r.domain(), r.target(), "http", r.remark());
                    log.info("新增域名解析 {} -> {} (id={})", r.domain(), r.target(), id);
                    continue;
                }
                String oldTarget = exist.path("Target").path("TargetStr").asText("");
                if (!r.target().equals(oldTarget)) {
                    apiClient.editHost(s, exist.path("Id").asInt(), node.getNpsClientId(),
                            r.domain(), r.target(), "http", r.remark());
                    log.info("更新域名解析 {} -> {}（原 {}）", r.domain(), r.target(), oldTarget);
                }
            } catch (Exception e) {
                log.error("同步域名解析 {} 失败: {}", r.domain(), e.getMessage());
            }
        }

        for (Map.Entry<String, JsonNode> e : actual.entrySet()) {
            if (expected.containsKey(e.getKey())) {
                continue;
            }
            try {
                apiClient.deleteHost(s, e.getValue().path("Id").asInt());
                log.info("删除域名解析 {}", e.getKey());
            } catch (Exception ex) {
                log.warn("删除域名解析 {} 失败: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    private void removeAllRoutes(TunnelSetting s, TunnelNode node) {
        if (node.getNpsClientId() == null) {
            return;
        }
        try {
            JsonNode rows = apiClient.listHosts(s, node.getNpsClientId()).path("rows");
            for (JsonNode row : rows) {
                int id = row.path("Id").asInt();
                if (id > 0) {
                    apiClient.deleteHost(s, id);
                    log.info("删除域名解析 {}", row.path("Host").asText());
                }
            }
        } catch (Exception e) {
            log.warn("清理域名解析失败: {}", e.getMessage());
        }
    }

    /**
     * 应用的隧道路由：完整域名 → 节点上的 127.0.0.1:端口。
     */
    public Route routeOf(App app, TunnelSetting s) {
        String sub = effectiveSubdomain(app);
        if (StrUtil.isBlank(sub)) {
            throw new BusinessException("子域名为空");
        }
        int port = resolveLocalPort(app);
        return new Route(sub + "." + s.getSubDomainHost(), "127.0.0.1:" + port, "app:" + app.getName());
    }

    /**
     * 应用实际暴露到主机侧的端口。
     * <p>
     * bridge 模式取主机映射端口；host 模式取容器端口。
     */
    public int resolveLocalPort(App app) {
        App.AppConfig cfg = app.getConfig();
        Assert.notNull(cfg, "应用未配置容器参数");
        String mode = cfg.getNetworkMode();
        if ("none".equalsIgnoreCase(mode)) {
            throw new BusinessException("应用网络模式为 none，无法暴露端口");
        }
        Integer selected = app.getTunnelPort();
        if (selected == null || selected <= 0) {
            throw new BusinessException("请选择要暴露的端口");
        }
        boolean bridge = StrUtil.isBlank(mode) || "bridge".equalsIgnoreCase(mode);
        if (cfg.getPorts() != null) {
            for (App.PortBinding p : cfg.getPorts()) {
                if (p == null || !Objects.equals(p.getPrivatePort(), selected)) {
                    continue;
                }
                if (bridge) {
                    if (p.getPublicPort() == null) {
                        throw new BusinessException("端口 " + selected
                                + " 未映射到主机端口，隧道无法访问；请先在【容器配置】里填写主机端口");
                    }
                    return p.getPublicPort();
                }
                return selected;
            }
        }
        // 手输的端口不在应用端口列表里：按主机侧端口直接使用
        return selected;
    }

    /**
     * 子域名默认取应用名称（按 DNS label 规范化）。
     */
    public static String slug(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        if (s.length() > 63) {
            s = s.substring(0, 63).replaceAll("-+$", "");
        }
        return s;
    }

    public String effectiveSubdomain(App app) {
        return StrUtil.blankToDefault(slug(app.getTunnelSubdomain()), slug(app.getName()));
    }

    private void assertSubdomainFree(App app, String sub) {
        for (App other : appRepository.findAll()) {
            if (other.getId() != null && other.getId().equals(app.getId())) {
                continue;
            }
            if (!Boolean.TRUE.equals(other.getTunnelEnabled())) {
                continue;
            }
            if (sub.equalsIgnoreCase(effectiveSubdomain(other))) {
                throw new BusinessException("子域名已被应用【" + other.getName() + "】占用：" + sub);
            }
        }
    }

    private List<App> tunnelApps(String hostId) {
        return appRepository.findAllByHost_Id(hostId).stream()
                .filter(a -> Boolean.TRUE.equals(a.getTunnelEnabled()))
                .toList();
    }

    /**
     * 主机地址（用于展示内网访问地址）。
     */
    public static String hostAddress(Host host) {
        if (host == null) {
            return null;
        }
        if (host.isSsh()) {
            return host.getSshHost();
        }
        String dh = host.getDockerHost();
        if (StrUtil.isBlank(dh)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(dh.contains("://") ? dh : "tcp://" + dh);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private HostConfig baseHostConfig() {
        return new HostConfig()
                .withNetworkMode("host")
                .withRestartPolicy(RestartPolicy.alwaysRestart())
                .withLogConfig(new LogConfig(LogConfig.LoggingType.DEFAULT, Map.of("max-size", "50m")));
    }

    private Container findContainer(DockerClient client, String name) {
        for (Container c : client.listContainersCmd().withShowAll(true).withNameFilter(List.of(name)).exec()) {
            if (name.equals(firstName(c))) {
                return c;
            }
        }
        return null;
    }

    /**
     * 一条隧道路由。
     */
    public record Route(String domain, String target, String remark) {
    }

}
