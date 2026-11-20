package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.dto.HostUsageVo;
import io.github.jiangood.docker.admin.service.ContainerUsageService;
import io.github.jiangood.docker.admin.service.HostUsageSampleService;
import io.github.jiangood.docker.admin.service.HostUsageService;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主机用量采集（每 1 分钟）：等价于 {@code docker stats} 的一轮快照，既是页面的数据源，
 * 也是主机历史曲线与容器用量快照的采样点。
 * <p>
 * 与页面轮询的关系：页面只读库/快照，不再自己采集，所以无论开多少个标签页，
 * 主机侧都只有这里每轮一次的开销；用户点「刷新」时可以就地现采一轮。
 * <p>
 * 任务实例由 Quartz 每次触发重建，不能跨轮保存状态；退避计数放在 {@link HostUsageService} 里。
 * {@link BaseJob} 带 {@code @DisallowConcurrentExecution}，一轮最坏十几秒，不会和下一次重叠。
 */
@Component
@JobDescription(label = "主机用量采集")
public class HostUsageCollectJob extends BaseJob {

    @Resource
    private HostUsageService hostUsageService;

    @Resource
    private HostUsageSampleService hostUsageSampleService;

    @Resource
    private ContainerUsageService containerUsageService;

    @Override
    public String execute(JobDataMap data, Logger logger) {
        progress("开始采集主机用量");

        HostUsageService.CollectResult result = hostUsageService.collectAll();
        List<HostUsageVo> reachable = result.values().stream()
                .filter(HostUsageVo::isReachable)
                .toList();

        int sampleRows = 0;
        int containerRows = 0;
        if (!reachable.isEmpty()) {
            try {
                sampleRows = hostUsageSampleService.saveBatch(reachable);
            } catch (Exception e) {
                // 落库失败不影响采集本身：内存里已有最新值，下一轮还会再写
                logger.warn("写入主机用量采样失败：{}", e.toString());
            }
            for (HostUsageVo vo : reachable) {
                try {
                    containerRows += containerUsageService.replaceHost(
                            vo.getHostId(), vo.getName(), vo.getCollectedAt(), vo.getContainers());
                } catch (Exception e) {
                    logger.warn("写入主机 {} 容器用量快照失败：{}", vo.getName(), e.toString());
                }
            }
        }

        // 离线 / 采集失败的主机：清空容器快照，避免展示过期数据
        for (HostUsageVo vo : result.values()) {
            if (!vo.isReachable()) {
                logger.warn("主机 {} 用量采集失败：{}", vo.getName(), vo.getError());
                try {
                    containerUsageService.clearHost(vo.getHostId());
                } catch (Exception e) {
                    logger.warn("清理主机 {} 容器用量快照失败：{}", vo.getName(), e.toString());
                }
            }
        }
        // 退避跳过的主机：本轮未采集，同样清空容器快照
        if (result.skippedHostIds() != null) {
            for (String hostId : result.skippedHostIds()) {
                try {
                    containerUsageService.clearHost(hostId);
                } catch (Exception e) {
                    logger.warn("清理退避主机 {} 容器用量快照失败：{}", hostId, e.toString());
                }
            }
        }

        try {
            hostUsageSampleService.pruneIfDue();
        } catch (Exception e) {
            logger.warn("清理过期用量采样失败：{}", e.toString());
        }

        progress("采集完成");
        return result.describe() + "，主机采样 " + sampleRows + " 条，容器快照 " + containerRows + " 条";
    }
}
