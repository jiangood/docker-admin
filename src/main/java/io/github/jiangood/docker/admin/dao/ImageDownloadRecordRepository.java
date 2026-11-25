package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.ImageDownloadRecord;
import io.github.jiangood.openadmin.framework.data.BaseRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImageDownloadRecordRepository extends BaseRepository<ImageDownloadRecord, String> {

    Optional<ImageDownloadRecord> findByLogId(String logId);

    /**
     * 批量清理时锁定单条记录，避免多人同时清理同一归档，也避免与任务收尾互相覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ImageDownloadRecord r where r.logId = :logId")
    Optional<ImageDownloadRecord> findByLogIdForUpdate(@Param("logId") String logId);

    List<ImageDownloadRecord> findByStatus(String status);

}
