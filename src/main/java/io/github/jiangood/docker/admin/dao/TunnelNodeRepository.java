package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.TunnelNode;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface TunnelNodeRepository extends BaseRepository<TunnelNode, String> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);

}
