package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.TunnelClientRepository;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.TunnelClient;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 隧道（http-tunnel）：平台通过每个客户端自身的隧道管理 API（{@code --api-port}）维护隧道。
 * <p>
 * 客户端进程运行在各业务主机上，平台只保存其名称、令牌、API 地址与域名，不部署容器、不生成配置。
 * 应用在详情页「隧道」标签里显式选择客户端，完整域名 = 域名前缀 + "." + 客户端域名；
 * 目标地址在客户端主机上解析（{@code 127.0.0.1:<主机端口>}）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TunnelService {

    private final TunnelClientRepository clientRepository;

    private final AppRepository appRepository;

    @Resource
    HttpTunnelApiClient api;

    // ------------------------------------------------------------------ 客户端

    /**
     * 客户端列表：本地登记 + 尽力读取客户端实时状态。
     */
    public List<Map<String, Object>> listClients() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TunnelClient c : clientRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))) {
            list.add(clientItem(c));
        }
        return list;
    }

    private Map<String, Object> clientItem(TunnelClient c) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", c.getId());
        item.put("name", c.getName());
        item.put("apiUrl", c.getApiUrl());
        item.put("domain", c.getDomain());
        item.put("remark", c.getRemark());
        item.put("hasToken", StrUtil.isNotBlank(c.getToken()));
        item.put("tokenMasked", c.getTokenMasked());
        item.put("configured", c.configured());

        JsonNode status = null;
        String statusError = null;
        if (c.configured()) {
            try {
                status = api.status(c);
            } catch (Exception e) {
                statusError = e.getMessage();
            }
        }
        item.put("connected", status == null ? null : status.path("connected").asBoolean(false));
        item.put("tunnelCount", status == null ? null : status.path("tunnels").asInt(0));
        item.put("statusError", statusError);
        return item;
    }

    /**
     * 新增 / 修改客户端（仅本地登记，客户端进程由用户自行部署）。
     */
    @Transactional
    public TunnelClient saveClient(TunnelClient input) {
        Assert.notNull(input, "参数不能为空");
        boolean create = StrUtil.isBlank(input.getId());
        TunnelClient c;
        if (create) {
            String name = StrUtil.trim(input.getName());
            Assert.hasText(name, "请填写客户端名称");
            assertClientName(name);
            Assert.isTrue(!clientRepository.existsByName(name), "客户端名称已存在：" + name);
            c = new TunnelClient();
            c.setName(name);
        } else {
            c = requireClient(input.getId());
        }

        Assert.hasText(input.getApiUrl(), "请填写客户端 API 地址");
        c.setApiUrl(StrUtil.trim(input.getApiUrl()));
        c.setDomain(StrUtil.trimToNull(input.getDomain()));
        c.setRemark(StrUtil.trimToNull(input.getRemark()));
        if (StrUtil.isNotBlank(input.getToken())) {
            c.setToken(input.getToken().trim());
        } else if (StrUtil.isBlank(c.getToken())) {
            c.setToken(RandomUtil.randomString(32));
        }
        return clientRepository.save(c);
    }

    /**
     * 删除客户端：先尽力删除其在客户端侧的隧道，再清理引用它的应用（关闭隧道并解除引用），最后删本地登记。
     */
    @Transactional
    public void deleteClient(String id) {
        TunnelClient c = requireClient(id);

        if (c.configured()) {
            try {
                JsonNode arr = api.listTunnels(c);
                if (arr != null && arr.isArray()) {
                    for (JsonNode t : arr) {
                        String domain = text(t, "name");
                        if (StrUtil.isNotBlank(domain)) {
                            try {
                                api.deleteTunnel(c, domain);
                            } catch (Exception e) {
                                log.warn("删除客户端 {} 的隧道 {} 失败：{}", c.getName(), domain, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取客户端 {} 的隧道失败：{}", c.getName(), e.getMessage());
            }
        }

        List<App> affected = appRepository.findAllByTunnelClient_Id(c.getId());
        for (App app : affected) {
            app.setTunnelEnabled(false);
            app.setTunnelClient(null);
        }
        appRepository.saveAll(affected);
        appRepository.flush();
        clientRepository.deleteById(c.getId());
    }

    /**
     * 测试客户端管理 API 连通性，返回其 {@code /api/status}。
     */
    public JsonNode testClient(String id) {
        TunnelClient c = requireClient(id);
        Assert.isTrue(c.configured(), "客户端「" + c.getName() + "」未配置 API 地址或令牌");
        return api.status(c);
    }

    private TunnelClient requireClient(String id) {
        Assert.hasText(id, "缺少客户端 id");
        TunnelClient c = clientRepository.findById(id).orElse(null);
        Assert.notNull(c, "客户端不存在");
        return c;
    }

    private static void assertClientName(String name) {
        Assert.isTrue(name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                "客户端名称只能包含字母、数字、点、下划线和短横线：" + name);
    }

    // ------------------------------------------------------------------ 隧道

    /**
     * 全部隧道（逐个客户端调用其管理 API 聚合：域名 → 目标地址）。
     * <p>
     * 隧道由应用详情页的「隧道」标签维护，这里只提供只读列表。
     */
    public List<Map<String, Object>> listTunnels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TunnelClient c : clientRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))) {
            if (!c.configured()) {
                continue;
            }
            try {
                JsonNode arr = api.listTunnels(c);
                if (arr == null || !arr.isArray()) {
                    continue;
                }
                for (JsonNode t : arr) {
                    String domain = text(t, "name");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("client", c.getName());
                    item.put("domain", domain);
                    item.put("localAddr", text(t, "local_addr"));
                    item.put("url", domainUrl(domain));
                    list.add(item);
                }
            } catch (Exception e) {
                log.warn("读取客户端 {} 的隧道失败：{}", c.getName(), e.getMessage());
            }
        }
        return list;
    }

    /**
     * 新增 / 修改隧道（PUT 幂等）。域名在服务端全局唯一。
     */
    public void putTunnel(TunnelClient client, String domain, String localAddr) {
        Assert.notNull(client, "缺少客户端");
        Assert.isTrue(client.configured(), "客户端「" + client.getName() + "」未配置 API 地址或令牌");
        Assert.hasText(localAddr, "请填写目标地址");
        api.putTunnel(client, normalizeDomain(domain), StrUtil.trim(localAddr));
    }

    // ------------------------------------------------------------------ 应用隧道

    /**
     * 应用详情页「隧道」标签所需的元数据。
     */
    public Map<String, Object> appTunnelMeta(String appId) {
        App app = requireApp(appId);
        Host host = app.getHost();

        String prefix = app.getTunnelPrefix();
        if (StrUtil.isBlank(prefix)) {
            prefix = slug(app.getName());
        }

        TunnelClient selected = app.getTunnelClient();

        List<Map<String, Object>> clients = new ArrayList<>();
        for (TunnelClient c : clientRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("domain", c.getDomain());
            item.put("configured", c.configured());
            clients.add(item);
        }

        String fullDomain = (StrUtil.isNotBlank(prefix) && selected != null
                && StrUtil.isNotBlank(selected.getDomain()))
                ? prefix + "." + selected.getDomain() : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", Boolean.TRUE.equals(app.getTunnelEnabled()));
        data.put("prefix", prefix);
        data.put("clientId", selected == null ? null : selected.getId());
        data.put("client", selected == null ? null : selected.getName());
        data.put("domain", selected == null ? null : selected.getDomain());
        data.put("fullDomain", fullDomain);
        data.put("url", fullDomain == null ? null : domainUrl(fullDomain));
        data.put("hostName", host == null ? null : host.getName());
        data.put("port", app.getTunnelPort());
        data.put("ports", portOptions(app));
        data.put("clients", clients);
        return data;
    }

    /**
     * 应用隧道开关：开启时按 域名前缀 + 所选客户端域名 拼出完整域名并写入客户端，
     * 关闭、换客户端或改前缀时清理旧映射。
     */
    @Transactional
    public App saveAppTunnel(String appId, boolean enabled, String clientId, String prefix, Integer port) {
        App app = requireApp(appId);

        if (enabled) {
            Assert.hasText(clientId, "请选择隧道客户端");
            TunnelClient client = requireClient(clientId);
            Assert.isTrue(client.configured(), "客户端「" + client.getName() + "」未配置 API 地址或令牌");
            Assert.hasText(client.getDomain(), "隧道客户端「" + client.getName() + "」未配置域名");
            String p = slug(StrUtil.blankToDefault(StrUtil.trim(prefix), app.getName()));
            Assert.hasText(p, "请填写域名前缀");
            int hostPort = resolveHostPort(app, port);
            String fullDomain = normalizeDomain(p + "." + client.getDomain());

            cleanupApplied(app, client, fullDomain);
            api.putTunnel(client, fullDomain, "127.0.0.1:" + hostPort);

            app.setTunnelEnabled(true);
            app.setTunnelPrefix(p);
            app.setTunnelPort(port);
            app.setTunnelClient(client);
        } else {
            cleanupApplied(app, null, null);
            app.setTunnelEnabled(false);
            app.setTunnelClient(null);
        }
        return appRepository.save(app);
    }

    /**
     * 清理应用上一次下发到客户端的隧道映射。
     * 换客户端时删除旧客户端的映射；同一客户端改前缀时删除旧域名；域名未变则跳过（PUT 幂等覆盖）。
     */
    private void cleanupApplied(App app, TunnelClient target, String newDomain) {
        if (!Boolean.TRUE.equals(app.getTunnelEnabled())) {
            return;
        }
        TunnelClient old = app.getTunnelClient();
        String oldDomain = appliedDomain(app, old);
        if (oldDomain == null) {
            return;
        }
        boolean sameClient = target != null && old != null && StrUtil.equals(target.getId(), old.getId());
        boolean sameDomain = newDomain != null && oldDomain.equalsIgnoreCase(newDomain);
        if (sameClient && sameDomain) {
            return;
        }
        if (old != null && old.configured()) {
            try {
                api.deleteTunnel(old, oldDomain);
            } catch (Exception e) {
                log.warn("清理应用 {} 的隧道 {} 失败：{}", app.getName(), oldDomain, e.getMessage());
            }
        }
    }

    /**
     * 删除应用时清理其隧道（尽力而为，失败只记日志，不阻断删除）。
     */
    public void removeAppTunnel(App app) {
        if (app == null || !Boolean.TRUE.equals(app.getTunnelEnabled())) {
            return;
        }
        try {
            TunnelClient client = app.getTunnelClient();
            String old = appliedDomain(app, client);
            if (old != null && client != null && client.configured()) {
                api.deleteTunnel(client, old);
            }
        } catch (Exception e) {
            log.warn("删除应用 {} 的隧道失败：{}", app.getName(), e.getMessage());
        }
    }

    /**
     * 依据应用已保存的前缀与客户端域名，重建上次下发的完整域名。
     */
    private static String appliedDomain(App app, TunnelClient client) {
        if (!Boolean.TRUE.equals(app.getTunnelEnabled())
                || StrUtil.isBlank(app.getTunnelPrefix())
                || client == null || StrUtil.isBlank(client.getDomain())) {
            return null;
        }
        return normalizeDomain(app.getTunnelPrefix() + "." + client.getDomain());
    }

    /**
     * 应用实际暴露到主机侧的端口：bridge 取主机映射端口，host 取容器端口。
     */
    private int resolveHostPort(App app, Integer containerPort) {
        App.AppConfig cfg = app.getConfig();
        Assert.notNull(cfg, "应用未配置容器参数");
        Assert.isTrue(!"none".equalsIgnoreCase(cfg.getNetworkMode()), "应用网络模式为 none，无法暴露端口");
        Assert.notNull(containerPort, "请选择要暴露的端口");
        boolean host = "host".equalsIgnoreCase(cfg.getNetworkMode());
        if (cfg.getPorts() != null) {
            for (App.PortBinding p : cfg.getPorts()) {
                if (p == null || !Objects.equals(p.getPrivatePort(), containerPort)) {
                    continue;
                }
                if (host) {
                    return containerPort;
                }
                Assert.notNull(p.getPublicPort(),
                        "端口 " + containerPort + " 未映射到主机端口，隧道无法访问；请先在【容器配置】里填写主机端口");
                return p.getPublicPort();
            }
        }
        return containerPort;
    }

    private List<Map<String, Object>> portOptions(App app) {
        List<Map<String, Object>> ports = new ArrayList<>();
        App.AppConfig cfg = app.getConfig();
        if (cfg == null || cfg.getPorts() == null) {
            return ports;
        }
        boolean host = "host".equalsIgnoreCase(cfg.getNetworkMode());
        for (App.PortBinding p : cfg.getPorts()) {
            if (p == null || p.getPrivatePort() == null) {
                continue;
            }
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
        return ports;
    }

    private App requireApp(String appId) {
        Assert.hasText(appId, "缺少应用 id");
        App app = appRepository.findById(appId).orElse(null);
        Assert.notNull(app, "应用不存在");
        return app;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 域名规范化：去掉协议头与路径、转小写、去尾点，并做基本格式校验。
     */
    public static String normalizeDomain(String domain) {
        Assert.hasText(domain, "域名不能为空");
        String d = domain.trim().toLowerCase(Locale.ROOT);
        d = StrUtil.removePrefix(d, "http://");
        d = StrUtil.removePrefix(d, "https://");
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        d = StrUtil.removeSuffix(d, ".");
        Assert.isTrue(d.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?"), "域名格式不正确：" + domain);
        return d;
    }

    /**
     * 按域名拼出隧道的访问地址（HTTP）。
     */
    public static String domainUrl(String domain) {
        if (StrUtil.isBlank(domain)) {
            return null;
        }
        return "http://" + domain;
    }

    /**
     * 域名前缀默认取应用名称（按 DNS label 规范化）。
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

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
