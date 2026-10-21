package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ImageSyncLogRepository;
import io.github.jiangood.docker.admin.entity.ImageSyncLog;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 镜像同步记录：同步开始时落库（success 为空表示进行中），结束时补全结果。
 * <p>
 * 记录在 {@code @Async} 的同步任务里写入，因此每个方法自带事务、立即 flush，
 * 前端发起同步后马上就能在列表里看到「同步中」这一条。
 */
@Service
@RequiredArgsConstructor
public class ImageSyncLogService extends BaseService<ImageSyncLog> {

    /**
     * 失败原因过长时截断，避免超出列长度导致记录写不进去（同步本身的结果更重要）。
     */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final ImageSyncLogRepository imageSyncLogRepository;

    /**
     * 开始一次同步：先落库，后续字段（主机名、注册中心地址、目标镜像）由同步过程补全。
     */
    @Transactional
    public ImageSyncLog start(String logId, String syncType, String sourceImage, String hostId, String targetHostId) {
        ImageSyncLog log = new ImageSyncLog();
        log.setLogId(logId);
        log.setSyncType(syncType);
        log.setSourceImage(StrUtil.trim(sourceImage));
        log.setHostId(hostId);
        log.setTargetHostId(targetHostId);
        return imageSyncLogRepository.saveAndFlush(log);
    }

    /**
     * 结束一次同步：写入结果、耗时并保存。{@code log} 为 null（记录都没建起来）时不处理。
     */
    @Transactional
    public void finish(ImageSyncLog log, boolean success, String errorMessage) {
        if (log == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        log.setSuccess(success);
        log.setCompleteTime(now);
        if (!success) {
            log.setErrorMessage(StrUtil.sub(StrUtil.blankToDefault(errorMessage, "同步失败"), 0, MAX_ERROR_LENGTH));
        }
        if (log.getCreateTime() != null) {
            log.setTimeSpend(Duration.between(log.getCreateTime(), now).toMillis());
        }
        imageSyncLogRepository.saveAndFlush(log);
    }
}
