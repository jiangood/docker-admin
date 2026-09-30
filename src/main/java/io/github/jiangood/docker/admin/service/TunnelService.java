package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.TunnelNodeRepository;
import io.github.jiangood.docker.admin.dao.TunnelRepository;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Tunnel;
import io.github.jiangood.docker.admin.entity.TunnelNode;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.github.jiangood.docker.admin.service.TunnelDeployService.ACTION_DELETE_NODE;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.ACTION_DEPLOY_ALL_FRPC;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.ACTION_DEPLOY_FRPC;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.ACTION_REDEPLOY_FRPC;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.ACTION_REMOVE_FRPC;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.ROLE_FRPC;
import static io.github.jiangood.docker.admin.service.TunnelDeployService.roleLabels;

/**
 * 隧道（frp）：平台保存连接配置、按节点生成 frpc 配置并重建容器，同时维护落库的隧道（子域名 → 应用端口）。
 * <p>
 * frps 服务端不由平台下发，页面只提供配置与 docker 部署命令，由用户在主机上手动部署；
 * 新增 / 修改 / 删除隧道后会自动重新生成所属节点的 frpc.toml 并重建该 frpc 容器。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TunnelService extends BaseService<TunnelSetting> {

    private final TunnelNodeRepository nodeRepository;
    private final TunnelRepository tunnelRepository;

    @Resource
    AppRepository appRepository;

    @Resource
    HostService hostService;

    @Resource
    FrpConfigManager confManager;

    @Resource
    TunnelDeployService deployService;

    @Resource
    DockerClientManager dockerManager;

    @Resource
    RegistryService registryService;

    // ------------------------------------------------------------------ 设置

    /**
     * 生效的隧道设置（数据库中的最新一条）。
     */
    public TunnelSetting getSetting() {
        List<TunnelSetting> list = findAll(Sort.by(Sort.Direction.DESC, "updateTime"));
        return list.isEmpty() ? null : list.get(0);
    }

    public TunnelSetting requireSetting() {
        TunnelSetting s = getSetting();
        Assert.notNull(s, "尚未配置隧道，请先在【隧道管理】页面填写配置并保存");
        return s;
    }

    public TunnelSetting requireConfiguredSetting() {
        TunnelSetting s = requireSetting();
        Assert.isTrue(s.configured(), "隧道设置不完整：请先在【服务端】填写连接地址与域名后缀");
        return s;
    }

    /**
     * 页面初始化：设置（token 不回传）+ frps 配置与手动部署命令。
     * <p>
     * frps 由用户在主机上手动部署，这里返回真实的 {@code frps.toml}（含 token）与
     * docker 命令；{@code frpsConf} 仍是 token 掩码的预览，供【服务端】页签使用。
     */
    public Map<String, Object> info() {
        TunnelSetting s = getSetting();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("setting", s);
        data.put("frpsConf", s == null ? null : confManager.frpsConfForView(s));
        data.put("frpsToml", s == null ? null : confManager.frpsConf(s));
        data.put("frpsCommand", s == null ? null : frpsDockerCommand(s));
        return data;
    }

    /**
     * 手动部署 frps 的 docker 命令：配置以 {@code -v} 挂载宿主机上的 {@code frps.toml}，
     * 与平台部署 frpc 时保持一致的 host 网络与重启策略。
     */
    private String frpsDockerCommand(TunnelSetting s) {
        String conf = FrpConfigManager.FRPS_CONF;
        return "docker run -d \\\n"
                + "  --name " + TunnelSetting.FRPS_CONTAINER + " \\\n"
                + "  --restart always \\\n"
                + "  --network host \\\n"
                + "  -v " + conf + ":" + conf + ":ro \\\n"
                + "  " + s.frpsImage();
    }

    /**
     * 保存隧道行为配置（连接地址 / 域名后缀 / 端口 / token 等）。
     * <p>
     * frps 由用户手动部署，此处只保存生成 frps.toml 所需的连接参数；
     * frpc 的客户端镜像由 {@link #saveDeployFrpc} 单独维护，两者互不覆盖。
     */
    @Transactional
    public TunnelSetting saveSetting(TunnelSetting input) {
        Assert.notNull(input, "参数不能为空");
        Assert.hasText(input.frpsAddr(), "请填写连接地址");
        Assert.hasText(input.subDomainHost(), "请填写域名后缀");

        TunnelSetting target = current();
        target.setFrpsAddr(StrUtil.trim(input.getFrpsAddr()));
        target.setBindPort(input.getBindPort());
        target.setVhostHttpPort(input.getVhostHttpPort());
        target.setSubDomainHost(StrUtil.trim(input.getSubDomainHost()));
        target.setTransportTls(input.getTransportTls());
        if (StrUtil.isNotBlank(input.getAuthToken())) {
            target.setAuthToken(input.getAuthToken().trim());
        } else if (StrUtil.isBlank(target.getAuthToken())) {
            target.setAuthToken(RandomUtil.randomString(24));
        }
        return save(target);
    }

    /**
     * 保存 frpc 部署参数（客户端镜像）。
     */
    @Transactional
    public TunnelSetting saveDeployFrpc(TunnelSetting input) {
        Assert.notNull(input, "参数不能为空");
        TunnelSetting target = current();
        target.setFrpcImage(StrUtil.trim(input.getFrpcImage()));
        return save(target);
    }

    private TunnelSetting current() {
        TunnelSetting old = getSetting();
        return old != null ? old : new TunnelSetting();
    }

    // ------------------------------------------------------------------ 部署（异步，返回 logId）

    public String deployFrpc(String nodeId) {
        requireConfiguredSetting();
        requireNode(nodeId);
        return deployService.submit(ACTION_DEPLOY_FRPC, nodeId);
    }

    public String removeFrpc(String nodeId) {
        requireNode(nodeId);
        return deployService.submit(ACTION_REMOVE_FRPC, nodeId);
    }

    /**
     * 按最新设置重建全部节点的 frpc（改了 token / 连接地址 / 端口后用）。
     */
    public String rebuildAllFrpc() {
        requireConfiguredSetting();
        return deployService.submit(ACTION_DEPLOY_ALL_FRPC, null);
    }

    // ------------------------------------------------------------------ 节点

    /**
     * 节点列表：含主机、容器实时状态与隧道数量。
     */
    public List<Map<String, Object>> listNodes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TunnelNode node : nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "createTime"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("remark", node.getRemark());
            item.put("hostId", node.getHost() == null ? null : node.getHost().getId());
            item.put("hostName", node.getHost() == null ? null : node.getHost().getName());
            item.put("containerId", node.getContainerId());
            item.put("lastDeployTime", node.getLastDeployTime());
            item.put("lastError", node.getLastError());
            item.put("tunnelCount", tunnelRepository.findAllByNode_Id(node.getId()).size());
            item.put("state", node.getHost() == null ? null
                    : containerState(node.getHost(), roleLabels(ROLE_FRPC, node.getId())));
            list.add(item);
        }
        return list;
    }

    @Transactional
    public TunnelNode saveNode(TunnelNode input) {
        Assert.notNull(input, "参数不能为空");
        String name = StrUtil.trim(input.getName());
        Assert.hasText(name, "请填写节点名称");
        String hostId = input.getHost() == null ? null : input.getHost().getId();
        Assert.hasText(hostId, "请选择主机");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");

        boolean dup = StrUtil.isBlank(input.getId())
                ? nodeRepository.existsByName(name)
                : nodeRepository.existsByNameAndIdNot(name, input.getId());
        Assert.isTrue(!dup, "节点名称已存在：" + name);

        TunnelNode node = StrUtil.isBlank(input.getId()) ? new TunnelNode() : requireNode(input.getId());
        node.setName(name);
        node.setHost(host);
        node.setRemark(StrUtil.trim(input.getRemark()));
        return nodeRepository.save(node);
    }

    /**
     * 删除节点：连同该节点的隧道与 frpc 容器一起清理（异步，返回 logId）。
     */
    public String deleteNode(String id) {
        requireNode(id);
        return deployService.submit(ACTION_DELETE_NODE, id);
    }

    private TunnelNode requireNode(String nodeId) {
        Assert.hasText(nodeId, "请选择节点");
        TunnelNode node = nodeRepository.findById(nodeId).orElse(null);
        Assert.notNull(node, "节点不存在");
        return node;
    }

    // ------------------------------------------------------------------ 隧道

    public List<Map<String, Object>> listTunnels() {
        TunnelSetting s = getSetting();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tunnel t : tunnelRepository.findAll(Sort.by(Sort.Direction.DESC, "createTime"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId());
            item.put("name", t.getName());
            item.put("subdomain", t.getSubdomain());
            item.put("nodeId", t.getNode() == null ? null : t.getNode().getId());
            item.put("nodeName", t.getNode() == null ? null : t.getNode().getName());
            item.put("appId", t.getApp() == null ? null : t.getApp().getId());
            item.put("appName", t.getApp() == null ? null : t.getApp().getName());
            item.put("localIp", t.getLocalIp());
            item.put("localPort", t.getLocalPort());
            item.put("scheme", StrUtil.blankToDefault(t.getScheme(), "http"));
            item.put("remark", t.getRemark());
            item.put("url", accessUrl(s, t));
            list.add(item);
        }
        return list;
    }

    /**
     * 新增隧道：新增后自动重新生成所属节点的 frpc 配置并重建容器。
     * <p>
     * 不加 {@code @Transactional}：先让保存提交，异步重建任务才能读到这条新隧道。
     *
     * @return 部署任务的 logId
     */
    public String addTunnel(String nodeId, String appId, Integer port, String subdomain, String remark) {
        TunnelSetting s = requireConfiguredSetting();
        TunnelNode node = requireNode(nodeId);
        App app = requireApp(appId);

        String sub = StrUtil.blankToDefault(slug(subdomain), slug(app.getName()));
        assertSubdomain(sub);
        assertSubdomainFree(sub, null);

        int hostPort = resolveHostPort(app, port);
        String localIp = requireHostAddress(app.getHost());

        Tunnel t = new Tunnel();
        t.setName(sub);
        t.setNode(node);
        t.setApp(app);
        t.setSubdomain(sub);
        t.setLocalIp(localIp);
        t.setLocalPort(hostPort);
        t.setScheme("http");
        t.setRemark(StrUtil.blankToDefault(StrUtil.trim(remark), app.getName()));
        tunnelRepository.save(t);
        log.info("新增隧道 {} -> {}:{}（节点 {}）", sub + "." + s.subDomainHost(), localIp, hostPort, node.getName());
        return deployService.submit(ACTION_REDEPLOY_FRPC, node.getId());
    }

    /**
     * 修改隧道（子域名 / 端口 / 备注）。
     */
    public String editTunnel(String id, String subdomain, Integer port, String remark) {
        Assert.hasText(id, "缺少隧道 id");
        Tunnel t = tunnelRepository.findById(id).orElse(null);
        Assert.notNull(t, "隧道不存在");

        String sub = StrUtil.blankToDefault(slug(subdomain), t.getSubdomain());
        assertSubdomain(sub);
        assertSubdomainFree(sub, id);
        t.setSubdomain(sub);
        t.setName(sub);
        t.setRemark(StrUtil.trim(remark));

        if (t.getApp() != null) {
            t.setLocalIp(requireHostAddress(t.getApp().getHost()));
            // 编辑时端口直接填主机侧的目标端口，不再按容器端口二次换算
            if (port != null) {
                t.setLocalPort(port);
            }
        }
        tunnelRepository.save(t);
        return t.getNode() == null ? null : deployService.submit(ACTION_REDEPLOY_FRPC, t.getNode().getId());
    }

    /**
     * 删除隧道并重建所属节点的 frpc。
     */
    public String deleteTunnel(String id) {
        Assert.hasText(id, "缺少隧道 id");
        Tunnel t = tunnelRepository.findById(id).orElse(null);
        Assert.notNull(t, "隧道不存在");
        String nodeId = t.getNode() == null ? null : t.getNode().getId();
        tunnelRepository.delete(t);
        return nodeId == null ? null : deployService.submit(ACTION_REDEPLOY_FRPC, nodeId);
    }

    /**
     * 隧道表单所需的元数据：应用主机地址、可选端口、默认子域名。
     */
    public Map<String, Object> appMeta(String appId) {
        App app = requireApp(appId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", app.getId());
        data.put("name", app.getName());
        data.put("hostName", app.getHost() == null ? null : app.getHost().getName());
        data.put("hostAddress", hostAddress(app.getHost()));
        data.put("defaultSubdomain", slug(app.getName()));

        List<Map<String, Object>> ports = new ArrayList<>();
        App.AppConfig cfg = app.getConfig();
        String mode = cfg == null ? "bridge" : cfg.getNetworkMode();
        if (cfg != null && cfg.getPorts() != null) {
            for (App.PortBinding p : cfg.getPorts()) {
                if (p == null || p.getPrivatePort() == null) {
                    continue;
                }
                boolean host = "host".equalsIgnoreCase(mode);
                Integer hostPort = host ? p.getPrivatePort() : p.getPublicPort();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("privatePort", p.getPrivatePort());
                item.put("publicPort", p.getPublicPort());
                item.put("hostPort", hostPort);
                item.put("protocol", StrUtil.blankToDefault(p.getProtocol(), "tcp").toUpperCase(Locale.ROOT));
                item.put("label", "容器 " + p.getPrivatePort()
                        + (hostPort != null ? " → 主机 " + hostPort : "（未映射主机端口）"));
                ports.add(item);
            }
        }
        data.put("ports", ports);
        data.put("networkMode", mode);
        return data;
    }

    private App requireApp(String appId) {
        Assert.hasText(appId, "请选择应用");
        App app = appRepository.findById(appId).orElse(null);
        Assert.notNull(app, "应用不存在");
        return app;
    }

    private void assertSubdomain(String sub) {
        Assert.isTrue(StrUtil.isNotBlank(sub), "子域名不能为空（应用名称为中文时请手动填写）");
        Assert.isTrue(sub.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"),
                "子域名只能包含小写字母、数字和短横线：" + sub);
    }

    private void assertSubdomainFree(String sub, String excludeId) {
        boolean exists = StrUtil.isBlank(excludeId)
                ? tunnelRepository.existsBySubdomainIgnoreCase(sub)
                : tunnelRepository.existsBySubdomainIgnoreCaseAndIdNot(sub, excludeId);
        Assert.isTrue(!exists, "子域名已被占用：" + sub);
    }

    private String accessUrl(TunnelSetting s, Tunnel t) {
        if (s == null || StrUtil.isBlank(s.subDomainHost()) || StrUtil.isBlank(t.getSubdomain())) {
            return null;
        }
        String host = t.getSubdomain() + "." + s.subDomainHost();
        return s.vhostHttpPort() == 80 ? "http://" + host : "http://" + host + ":" + s.vhostHttpPort();
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 容器实时状态（running / exited / created），未部署或查询失败返回 null。
     */
    private String containerState(Host host, Map<String, String> labelFilter) {
        if (host == null) {
            return null;
        }
        DockerClient client = null;
        try {
            client = getClient(host);
            List<Container> list = client.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(labelFilter)
                    .exec();
            return list.isEmpty() ? null : list.get(0).getState();
        } catch (Exception e) {
            log.debug("查询隧道容器状态失败: {}", e.getMessage());
            return null;
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    private DockerClient getClient(Host host) {
        io.github.jiangood.docker.admin.entity.Registry registry = registryService.getEffective();
        return registry != null ? dockerManager.getClient(host, registry) : dockerManager.getClient(host);
    }

    /**
     * 应用实际暴露到主机侧的端口：bridge 模式取主机映射端口，host 模式取容器端口。
     */
    public int resolveHostPort(App app, Integer containerPort) {
        App.AppConfig cfg = app.getConfig();
        Assert.notNull(cfg, "应用未配置容器参数");
        String mode = cfg.getNetworkMode();
        Assert.isTrue(!"none".equalsIgnoreCase(mode), "应用网络模式为 none，无法暴露端口");
        Assert.notNull(containerPort, "请选择要暴露的端口");
        boolean bridge = StrUtil.isBlank(mode) || "bridge".equalsIgnoreCase(mode);
        if (cfg.getPorts() != null) {
            for (App.PortBinding p : cfg.getPorts()) {
                if (p == null || !Objects.equals(p.getPrivatePort(), containerPort)) {
                    continue;
                }
                if (bridge) {
                    if (p.getPublicPort() == null) {
                        throw new BusinessException("端口 " + containerPort
                                + " 未映射到主机端口，隧道无法访问；请先在【容器配置】里填写主机端口");
                    }
                    return p.getPublicPort();
                }
                return containerPort;
            }
        }
        // 手输的端口不在应用端口列表里：按主机侧端口直接使用
        return containerPort;
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

    /**
     * 主机地址（隧道目标所在的地址）。
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
            // 本机（默认端点）时，从宿主机的角度即 127.0.0.1
            return Host.TYPE_LOCAL.equalsIgnoreCase(host.getConnectionType()) ? "127.0.0.1" : null;
        }
        try {
            URI uri = URI.create(dh.contains("://") ? dh : "tcp://" + dh);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String requireHostAddress(Host host) {
        String addr = hostAddress(host);
        Assert.hasText(addr, "应用所在主机没有可用的地址，无法作为隧道目标");
        return addr;
    }

}
