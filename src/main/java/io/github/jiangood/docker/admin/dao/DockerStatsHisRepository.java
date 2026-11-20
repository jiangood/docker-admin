package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.DockerStatsHis;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DockerStatsHisRepository extends BaseRepository<DockerStatsHis, String> {

    /**
     * 查询某主机一段时间内的容器历史行（按时间升序），用于聚合为主机趋势。
     */
    @Query("from DockerStatsHis s where s.hostId = :hostId and s.collectedAt >= :from and s.collectedAt < :to order by s.collectedAt asc")
    List<DockerStatsHis> findByHostIdAndTimeRange(@Param("hostId") String hostId,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to,
                                                  Pageable pageable);

    @Modifying
    @Query("delete from DockerStatsHis s where s.collectedAt < :cutoff")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
