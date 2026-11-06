package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.service.SiteMonitorService;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 网站可达性检查：每分钟扫一遍「启用且到期」的监控项逐个探测。
 * <p>
 * 间隔粒度是监控项级别的（1/5/10/30/60 分钟），所以统一用一条每分钟触发的任务
 * 按到期时间过滤，而不是每个监控项注册一条 Quartz 任务。单次探测有监控项超时兜底，
 * {@link BaseJob} 自带 {@code @DisallowConcurrentExecution}，上一轮没跑完不会重叠。
 * 日志聚合建议在任务定义上选「按小时」：一天 1440 次执行，按次留记录太多。
 */
@Component
@JobDescription(label = "网站可达性检查")
public class SiteMonitorCheckJob extends BaseJob {

    @Resource
    private SiteMonitorService siteMonitorService;

    @Override
    public String execute(JobDataMap data, Logger logger) {
        progress("开始网站可达性检查");

        int checked = siteMonitorService.runDueChecks();

        int pruned = 0;
        try {
            pruned = siteMonitorService.pruneLogsIfDue();
        } catch (Exception e) {
            // 历史清理失败不影响检查本身
            logger.warn("清理网站监控历史记录失败：{}", e.toString());
        }

        progress("检查完成");
        return "探测 " + checked + " 个站点" + (pruned > 0 ? "，清理历史记录 " + pruned + " 条" : "");
    }

}
