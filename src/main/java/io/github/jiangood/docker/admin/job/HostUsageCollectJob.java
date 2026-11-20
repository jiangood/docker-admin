package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.dto.HostUsageVo;
import io.github.jiangood.docker.admin.service.DockerStatsHisService;
import io.github.jiangood.docker.admin.service.DockerStatsService;
import io.github.jiangood.docker.admin.service.HostUsageService;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 主机/容器用量采集（每 1 分钟）：采集 docker stats，更新容器最新快照；
 * 每 10 分钟额外写入一份容器粒度历史（用于趋势图，按主机聚合展示）。
 */
@Component
@JobDescription(label = "主机用量采集")
public class HostUsageCollectJob extends BaseJob {

    @Resource
    private HostUsageService hostUsageService;

    @Resource
    private DockerStatsService dockerStatsService;

    @Resource
    private DockerStatsHisService dockerStatsHisService;

    @Override
    public String execute(JobDataMap data, Logger logger) {
        HostUsageService.CollectResult result = hostUsageService.collectAll();
        List<HostUsageVo> reachable = result.values().stream()
                .filter(HostUsageVo::isReachable)
                .toList();

        // 1) 最新快照：每轮更新（容器粒度）
        int snapshotRows = 0;
        for (HostUsageVo vo : reachable) {
            try {
                snapshotRows += dockerStatsService.replaceHost(
                        vo.getHostId(), vo.getName(), vo.getCpus(), vo.getMemoryTotal(),
                        vo.getCollectedAt(), vo.getContainers());
            } catch (Exception e) {
                logger.warn("写入主机 {} 容器用量快照失败：{}", vo.getName(), e.toString());
            }
        }

        // 2) 历史：每 10 分钟写一次（容器粒度，供趋势聚合）
        int hisRows = 0;
        Long hisTime = reachable.stream()
                .map(HostUsageVo::getCollectedAt)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        if (hisTime != null && dockerStatsHisService.isHistoryRound(hisTime)) {
            try {
                hisRows = dockerStatsHisService.saveBatch(reachable);
                dockerStatsHisService.markHistoryWritten(hisTime);
            } catch (Exception e) {
                logger.warn("写入容器用量历史失败：{}", e.toString());
            }
        }

        // 3) 离线 / 采集失败 / 退避跳过：清空最新快照，避免展示过期数据
        for (HostUsageVo vo : result.values()) {
            if (!vo.isReachable()) {
                logger.warn("主机 {} 用量采集失败：{}", vo.getName(), vo.getError());
                try {
                    dockerStatsService.clearHost(vo.getHostId());
                } catch (Exception e) {
                    logger.warn("清理主机 {} 容器用量快照失败：{}", vo.getName(), e.toString());
                }
            }
        }
        if (result.skippedHostIds() != null) {
            for (String hostId : result.skippedHostIds()) {
                try {
                    dockerStatsService.clearHost(hostId);
                } catch (Exception e) {
                    logger.warn("清理退避主机 {} 容器用量快照失败：{}", hostId, e.toString());
                }
            }
        }

        try {
            dockerStatsHisService.pruneIfDue();
        } catch (Exception e) {
            logger.warn("清理过期容器用量历史失败：{}", e.toString());
        }

        String msg = result.describe() + "，快照 " + snapshotRows + " 条";
        if (hisRows > 0 || (hisTime != null && !dockerStatsHisService.isHistoryRound(hisTime))) {
            // 报告本轮是否写了历史
        }
        if (hisRows > 0) {
            msg += "，历史 " + hisRows + " 条";
        }
        return msg;
    }
}
