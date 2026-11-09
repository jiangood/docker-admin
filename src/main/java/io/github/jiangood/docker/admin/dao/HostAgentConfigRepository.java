package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.HostAgentConfig;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface HostAgentConfigRepository extends BaseRepository<HostAgentConfig, String> {

    /**
     * 删除主机时一并删掉它的 Agent 配置，避免留下指向不存在主机的孤儿记录。
     */
    @Modifying
    @Query("delete from HostAgentConfig c where c.hostId = :hostId")
    int deleteByHostId(@Param("hostId") String hostId);

}
