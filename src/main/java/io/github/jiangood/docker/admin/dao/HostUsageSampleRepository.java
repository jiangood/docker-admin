package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.dto.HostUsageRawPoint;
import io.github.jiangood.docker.admin.entity.HostUsageSample;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface HostUsageSampleRepository extends BaseRepository<HostUsageSample, String> {

    /**
     * 取一段时间内的采样点（投影，不加载实体）。
     * <p>
     * 用 {@link Pageable} 限制行数：保留期可配，万一有人把 30 天改成 365 天，
     * 也不能让一次查询把整表捞进内存（曲线本来也只画出几百个点）。
     */
    @Query("""
            select new io.github.jiangood.docker.admin.dto.HostUsageRawPoint(
                s.sampleAt, s.cpuPercent, s.memPercent, s.memUsed, s.containersRunning,
                s.netRx, s.netTx, s.blockRead, s.blockWrite)
            from HostUsageSample s
            where s.hostId = :hostId and s.sampleAt >= :from and s.sampleAt < :to
            order by s.sampleAt asc
            """)
    List<HostUsageRawPoint> findPoints(@Param("hostId") String hostId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    @Modifying
    @Query("delete from HostUsageSample s where s.sampleAt < :cutoff")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
