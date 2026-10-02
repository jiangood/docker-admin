package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuildLogRepository extends BaseRepository<BuildLog, String> {

    @Modifying
    @Query("delete from BuildLog b where b.projectId = :projectId and b.success = false")
    int deleteErrorLogsByProjectId(@Param("projectId") String projectId);
}
