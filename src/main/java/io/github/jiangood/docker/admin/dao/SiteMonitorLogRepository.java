package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.SiteMonitorLog;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface SiteMonitorLogRepository extends BaseRepository<SiteMonitorLog, String> {

    /**
     * 定向删除某监控项的历史记录，随监控项删除一并执行，避免孤儿记录。
     */
    @Modifying
    @Query("delete from SiteMonitorLog l where l.monitorId = :monitorId")
    int deleteByMonitorId(@Param("monitorId") String monitorId);

    /**
     * 清理保留期之外的历史记录。
     */
    @Modifying
    @Query("delete from SiteMonitorLog l where l.createTime < :cutoff")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
