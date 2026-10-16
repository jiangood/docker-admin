package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.Optional;

public interface SystemConfigRepository extends BaseRepository<SystemConfig, String> {
    Optional<SystemConfig> findByConfigKey(String configKey);
}
