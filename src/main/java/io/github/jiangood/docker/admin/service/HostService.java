package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

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

}
