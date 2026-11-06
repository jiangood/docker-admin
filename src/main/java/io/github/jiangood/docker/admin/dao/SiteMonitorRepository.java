package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.SiteMonitor;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SiteMonitorRepository extends BaseRepository<SiteMonitor, String> {
}
