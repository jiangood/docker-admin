package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.dto.HostUsageVo;
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
 * 主机用量采集（每分钟）：等价于 {@code docker stats} 的一轮快照，既是页面的数据源，也是历史曲线的采样点。
 * <p>
 * 与页面轮询的关系：页面只读 {@link HostUsageService#snapshotAll()} 的快照，不再自己采集，
 * 所以无论开多少个标签页，主机侧都只有这里每分钟一次的开销。
 * <p>
 * 任务实例由 Quartz 每次触发重建，不能跨轮保存状态；退避计数放在 {@link HostUsageService} 里。
 * {@link BaseJob} 带 {@code @DisallowConcurrentExecution}，一轮最坏十几秒，不会和下一次重叠。
 * 日志聚合建议在任务定义上选「按小时」：一分钟一次 + 每次一条记录会留下海量执行记录。
 */
@Component
@JobDescription(label = "主机用量采集")
public class HostUsageCollectJob extends BaseJob {

    @Resource
    private HostUsageService hostUsageService;

    @Resource
    private HostUsageSampleService hostUsageSampleService;

    @Override
    public String execute(JobDataMap data, Logger logger) {
        progress("开始采集主机用量");

        HostUsageService.CollectResult result = hostUsageService.collectAll();
        List<HostUsageVo> reachable = result.values().stream()
                .filter(HostUsageVo::isReachable)
                .toList();

        int rows = 0;
        if (!reachable.isEmpty()) {
            try {
                rows = hostUsageSampleService.saveBatch(reachable);
            } catch (Exception e) {
                // 落库失败不影响采集本身：内存里已有最新值，下一轮还会再写
                logger.warn("写入主机用量采样失败：{}", e.toString());
            }
        }

        for (HostUsageVo vo : result.values()) {
            if (!vo.isReachable()) {
                logger.warn("主机 {} 用量采集失败：{}", vo.getName(), vo.getError());
            }
        }

        try {
            hostUsageSampleService.pruneIfDue();
        } catch (Exception e) {
            logger.warn("清理过期用量采样失败：{}", e.toString());
        }

        progress("采集完成");
        return result.describe() + "，写入采样 " + rows + " 条";
    }
}
