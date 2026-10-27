package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.HostProxySettings;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostService extends BaseService<Host> {

    private final HostRepository hostRepository;
    private final SystemConfigService systemConfigService;

    /**
     * 代理的五列：界面只提交 {@code proxyUrl}，由 {@link #normalize} 拆开。
     */
    private static final List<String> PROXY_FIELDS = List.of(
            Host.Fields.proxyType, Host.Fields.proxyHost, Host.Fields.proxyPort,
            Host.Fields.proxyUsername, Host.Fields.proxyPassword);

    /**
     * 获得镜像构建主机（系统默认 runner）。
     *
     * @return
     */
    public Host getDefaultDockerRunner() {
        String configuredId = systemConfigService.getDefaultBuildHostId();
        if (StrUtil.isNotBlank(configuredId)) {
            return hostRepository.findById(configuredId)
                    .orElseThrow(() -> new io.github.jiangood.openadmin.util.BusinessException(
                            "系统默认构建主机不存在，请到设置-全局设置重新选择"));
        }
        Spec<Host> q = Spec.of();
        q.eq(Host.Fields.isRunner, true);
        return hostRepository.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime")).stream().findFirst().orElse(null);
    }

    @Transactional
    public void deleteHost(String id) {
        Assert.hasText(id, "主机 ID 不能为空");
        if (id.equals(systemConfigService.getDefaultBuildHostId())) {
            throw new io.github.jiangood.openadmin.util.BusinessException("该主机是系统默认构建主机，请先在设置-全局设置中更换默认构建主机");
        }
        deleteById(id);
    }

    public long count() {
        return hostRepository.count();
    }

    /**
     * 保存主机：新建走 create，编辑走 update，并归一化连接配置。
     * <p>
     * 「构建节点」全局唯一：设为「是」时自动取消其他主机的该标记，
     * 保证同时只有一台构建主机，避免多处构建相互干扰。
     */
    @Transactional
    public Host saveHost(Host input, List<String> updateFields) {
        normalize(input);
        Assert.hasText(input.getIp(), "请填写主机 IP 地址");
        fillSshPassword(input);
        fillProxyPassword(input);

        // 界面把代理收敛成一行 proxyUrl（不落库），解析出的五个字段不在请求体里；
        // 编辑时要把它们补进更新列表，否则拆出来的值（包括「清空代理」）写不进库。
        List<String> fields = updateFields == null ? new ArrayList<>() : new ArrayList<>(updateFields);
        if (fields.remove(Host.Fields.proxyUrl)) {
            for (String field : PROXY_FIELDS) {
                if (!fields.contains(field)) {
                    fields.add(field);
                }
            }
        }

        // 先落库（create/update 只更新前端提交的字段），再看最终结果决定是否取消其他主机的标记
        Host saved = StrUtil.isBlank(input.getId()) ? create(input) : update(input, fields);
        if (Boolean.TRUE.equals(saved.getIsRunner())) {
            clearRunnerFlagOfOthers(saved.getId());
        }
        return saved;
    }

    /**
     * 启动时归一化历史数据：构建节点只保留一台（updateTime 最新的一台）。
     * 此前允许标记多台，构建会落到哪台不确定；这里收敛为唯一的一台，幂等。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void normalizeRunnerUniqueness() {
        Host runner = getDefaultDockerRunner();
        if (runner != null) {
            clearRunnerFlagOfOthers(runner.getId());
        }
    }

    /**
     * 取消其他主机的「构建节点」标记，保证构建主机唯一。
     */
    private void clearRunnerFlagOfOthers(String keepId) {
        Spec<Host> q = Spec.of();
        q.eq(Host.Fields.isRunner, true);
        if (StrUtil.isNotBlank(keepId)) {
            q.ne("id", keepId);
        }
        List<Host> others = hostRepository.findAll(q);
        if (others.isEmpty()) {
            return;
        }
        others.forEach(h -> h.setIsRunner(false));
        hostRepository.saveAll(others);
        log.info("构建节点唯一，已取消其他 {} 台主机的构建节点标记", others.size());
    }

    /**
     * 密码不回传前端，编辑时留空表示沿用原密码。
     */
    public void fillSshPassword(Host input) {
        if (input == null || StrUtil.isNotBlank(input.getSshPassword()) || StrUtil.isBlank(input.getId())) {
            return;
        }
        Host old = findById(input.getId()).orElse(null);
        if (old != null) {
            input.setSshPassword(old.getSshPassword());
        }
    }

    /**
     * 代理密码同样不回传前端：界面里显示为 {@link HostProxySettings#PASSWORD_MASK}，
     * 保存时保持 {@code ***}（或省略密码）表示沿用已保存的密码。
     * <p>
     * 只有「远程 TCP + 启用代理」时才去找旧值；切换连接方式或清空代理地址时
     * {@link #normalize} 已经把密码清空，这里不能把它又捞回来。
     */
    public void fillProxyPassword(Host input) {
        if (input == null || !Host.TYPE_TCP.equalsIgnoreCase(input.getConnectionType())) {
            return;
        }
        HostProxySettings settings = HostProxySettings.of(input);
        if (!settings.enabled()) {
            return;
        }

        if (settings.username() == null) {
            // 没写用户名就是匿名，库里残留的密码一并清掉
            input.setProxyPassword(null);
        } else if (settings.password() == null || HostProxySettings.PASSWORD_MASK.equals(settings.password())) {
            // 占位符 / 省略密码：沿用已保存的；新增主机没有旧值就按空密码处理
            if (StrUtil.isBlank(input.getId())) {
                input.setProxyPassword(null);
            } else {
                Host old = findById(input.getId()).orElse(null);
                input.setProxyPassword(old == null ? null : old.getProxyPassword());
            }
        }
        // 密码回填后再刷新一次回显值（***），避免把明文带到后续序列化
        input.setProxyUrl(HostProxySettings.of(input).displayUrl());
    }

    /**
     * 归一化主机连接配置并补全默认值。
     * <ul>
     *     <li>连接方式为空：按 dockerHost 前缀推断（unix 视为本机，否则远程 TCP）</li>
     *     <li>SSH：端口默认 22、用户默认 root</li>
     *     <li>非 SSH：dockerHost 允许为空（本机使用平台默认端点），缺少协议头时自动补全</li>
     *     <li>代理只在远程 TCP 下有意义，其它连接方式保存时清空代理配置</li>
     * </ul>
     */
    public void normalize(Host host) {
        if (host == null) {
            return;
        }
        host.setIp(StrUtil.trim(host.getIp()));
        // 自定义请求头：原样保留，空就是空（名称为空则不发送请求头）
        host.setHeaderName(StrUtil.trimToNull(host.getHeaderName()));
        host.setHeaderValue(StrUtil.trimToNull(host.getHeaderValue()));
        String dockerHost = StrUtil.trim(host.getDockerHost());
        String type = StrUtil.trim(host.getConnectionType());

        if (StrUtil.isBlank(type)) {
            type = StrUtil.startWithIgnoreCase(dockerHost, "unix") ? Host.TYPE_LOCAL : Host.TYPE_TCP;
        }
        host.setConnectionType(type);

        if (Host.TYPE_SSH.equalsIgnoreCase(type)) {
            clearProxy(host);
            if (host.getSshPort() == null) {
                host.setSshPort(22);
            }
            if (StrUtil.isBlank(host.getSshUser())) {
                host.setSshUser("root");
            }
            return;
        }

        if (Host.TYPE_TCP.equalsIgnoreCase(type)) {
            normalizeProxy(host);
        } else {
            clearProxy(host);
        }

        if (StrUtil.isBlank(dockerHost)) {
            host.setDockerHost(null);
            return;
        }

        if (Host.TYPE_TCP.equalsIgnoreCase(type)) {
            host.setDockerHost(normalizeTcpHost(dockerHost));
        } else if (!StrUtil.contains(dockerHost, "://")) {
            // 本机：dockerHost 为可选 socket 路径
            host.setDockerHost("unix://" + dockerHost);
        } else {
            host.setDockerHost(dockerHost);
        }
    }

    /**
     * 归一化并校验远程 TCP 的连接代理。
     * <p>
     * 界面只提交一个 {@code proxyUrl}（如 {@code http://user:pass@10.0.0.1:8080}），
     * 这里解析成五个落库字段；没有这个字段时兼容旧的「拆分字段」调用方式。
     * 解析完再把 {@code proxyUrl} 换成回显值（密码只保留 {@code ***}），
     * 避免明文密码留在实体里被再次序列化。
     */
    private void normalizeProxy(Host host) {
        String rawUrl = host.getProxyUrl();
        if (rawUrl != null) {
            if (StrUtil.isBlank(rawUrl)) {
                clearProxy(host);
            } else {
                HostProxySettings parsed = HostProxySettings.parse(rawUrl);
                host.setProxyType(parsed.type().code());
                host.setProxyHost(parsed.host());
                host.setProxyPort(parsed.port());
                host.setProxyUsername(parsed.username());
                host.setProxyPassword(parsed.password());
            }
        } else {
            normalizeSplitProxyFields(host);
        }
        host.setProxyUrl(HostProxySettings.of(host).displayUrl());
    }

    /**
     * 兼容直接提交 proxyType/proxyHost/... 的调用（旧前端或外部接口）。
     */
    private void normalizeSplitProxyFields(Host host) {
        HostProxySettings.Type type = HostProxySettings.Type.parse(host.getProxyType());
        if (type == HostProxySettings.Type.NONE) {
            clearProxy(host);
            return;
        }
        host.setProxyType(type.code());
        host.setProxyHost(StrUtil.trimToNull(host.getProxyHost()));
        host.setProxyUsername(StrUtil.trimToNull(host.getProxyUsername()));
        host.setProxyPassword(StrUtil.trimToNull(host.getProxyPassword()));
        if (host.getProxyPort() != null && host.getProxyPort() <= 0) {
            host.setProxyPort(null);
        }

        HostProxySettings settings = HostProxySettings.of(host);
        Assert.hasText(settings.host(), "请填写代理地址");
        Assert.notNull(settings.port(), "请填写代理端口");
        Assert.isTrue(settings.port() >= 1 && settings.port() <= 65535, "代理端口需在 1-65535 之间");
    }

    private void clearProxy(Host host) {
        host.setProxyType(HostProxySettings.Type.NONE.code());
        host.setProxyHost(null);
        host.setProxyPort(null);
        host.setProxyUsername(null);
        host.setProxyPassword(null);
        host.setProxyUrl("");
    }

    /**
     * 远程 TCP 地址补全：允许只填 IP/主机名，自动补 tcp:// 前缀（{@code https://} 原样保留，表示走 TLS）。
     * <p>
     * <b>不补默认端口</b>：端口留空即保持无端口，传输层会按 http/https 默认端口（80/443）访问，
     * 便于直接指向由反向代理暴露的域名；需要 2375 请在连接配置里显式填写。
     * <p>
     * 路径会被丢掉：{@code https://host/info} 也按 {@code https://host} 处理。
     */
    private String normalizeTcpHost(String dockerHost) {
        String raw = StrUtil.trim(dockerHost);
        boolean https = StrUtil.startWithIgnoreCase(raw, Host.HTTPS_PREFIX);
        String prefix = https ? Host.HTTPS_PREFIX : "tcp://";
        String authority = raw;
        int schemeEnd = authority.indexOf("://");
        if (schemeEnd >= 0) {
            authority = authority.substring(schemeEnd + 3);
        }
        try {
            URI uri = URI.create("tcp://" + authority);
            String host = uri.getHost();
            if (StrUtil.isNotBlank(host)) {
                return uri.getPort() < 0 ? prefix + host : prefix + host + ":" + uri.getPort();
            }
        } catch (Exception e) {
            log.warn("dockerHost 解析失败，按原值使用: {}", dockerHost);
        }
        return prefix + authority;
    }

}
