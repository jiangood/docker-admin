package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.TaskLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.Optional;

public interface TaskLogRepository extends BaseRepository<TaskLog, String> {

    Optional<TaskLog> findByLogId(String logId);
}
