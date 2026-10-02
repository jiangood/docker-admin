package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.TunnelClient;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface TunnelClientRepository extends BaseRepository<TunnelClient, String> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);

}
