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
        fillSshPassword(input);
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        return update(input, updateFields);
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
     * 远程 TCP 地址补全：允许只填 IP/主机名，自动补 tcp:// 与默认端口 2375。
     * 已带端口或完整协议头的原样保留。
     */
    private String normalizeTcpHost(String dockerHost) {
        String value = StrUtil.contains(dockerHost, "://") ? dockerHost : "tcp://" + dockerHost;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (StrUtil.isNotBlank(host)) {
                if (uri.getPort() < 0) {
                    return "tcp://" + host + ":2375";
                }
                return "tcp://" + host + ":" + uri.getPort();
            }
        } catch (Exception e) {
            log.warn("dockerHost 解析失败，按原值使用: {}", dockerHost);
        }
        return value;
    }

}
