package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.DeployLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;

public interface DeployLogRepository extends BaseRepository<DeployLog, String> {

    /** 某应用的部署记录，最新在前。 */
    List<DeployLog> findByAppIdOrderByCreateTimeDesc(String appId);
}
