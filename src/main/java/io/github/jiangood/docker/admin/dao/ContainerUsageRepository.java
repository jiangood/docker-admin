package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.ContainerUsage;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContainerUsageRepository extends BaseRepository<ContainerUsage, String> {

    List<ContainerUsage> findByHostId(String hostId);

    ContainerUsage findByAppId(String appId);

    ContainerUsage findByHostIdAndContainerId(String hostId, String containerId);

    @Modifying
    @Query("delete from ContainerUsage c where c.hostId = :hostId")
    int deleteByHostId(@Param("hostId") String hostId);

    /**
     * 全局按内存用量倒序取 TopN（忽略 memUsage 为空的记录）。
     */
    @Query("from ContainerUsage c where c.memUsage is not null order by c.memUsage desc")
    List<ContainerUsage> findTopByMemUsage(Pageable pageable);
}
