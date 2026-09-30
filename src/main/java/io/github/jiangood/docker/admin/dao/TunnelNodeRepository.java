package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.TunnelNode;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.Optional;

public interface TunnelNodeRepository extends BaseRepository<TunnelNode, String> {

    Optional<TunnelNode> findByHost_Id(String hostId);

    boolean existsByHost_Id(String hostId);

}
