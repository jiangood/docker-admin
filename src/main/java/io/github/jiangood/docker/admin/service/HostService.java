package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostService extends BaseService<Host> {

    private final HostRepository hostRepository;

    /**
     * 获得镜像构建主机（系统默认 runner）。
     *
     * @return
     */
    public Host getDefaultDockerRunner() {
        Spec<Host> q = Spec.of();
        q.eq(Host.Fields.isRunner, true);
        return hostRepository.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime")).stream().findFirst().orElse(null);
    }

    public long count() {
        return hostRepository.count();
    }

    /**
     * 保存主机：新建走 create，编辑走 update，并归一化连接配置。
     */
    @Transactional
    public Host saveHost(Host input, List<String> updateFields) {
        normalize(input);
        Assert.hasText(input.getIp(), "请填写主机 IP 地址");
        fillSshPassword(input);
        fillHeaderValue(input);
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        return update(input, updateFields);
    }

    /**
     * 请求头值不回传前端，编辑时留空表示沿用原值。
     */
    public void fillHeaderValue(Host input) {
        if (input == null || StrUtil.isNotBlank(input.getHeaderValue()) || StrUtil.isBlank(input.getId())) {
            return;
        }
        Host old = findById(input.getId()).orElse(null);
        if (old != null) {
            input.setHeaderValue(old.getHeaderValue());
        }
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
     * 归一化主机连接配置并补全默认值。
     * <ul>
     *     <li>连接方式为空：按 dockerHost 前缀推断（unix 视为本机，否则远程 TCP）</li>
     *     <li>SSH：端口默认 22、用户默认 root</li>
     *     <li>非 SSH：dockerHost 允许为空（本机使用平台默认端点），缺少协议头时自动补全</li>
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
            if (host.getSshPort() == null) {
                host.setSshPort(22);
            }
            if (StrUtil.isBlank(host.getSshUser())) {
                host.setSshUser("root");
            }
            return;
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
