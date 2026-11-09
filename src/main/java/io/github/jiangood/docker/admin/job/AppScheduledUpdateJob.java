package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 应用定时更新检查：每分钟扫一遍「开启定时更新且到期」的应用。
 * <p>
 * 间隔粒度是应用级别的（10 分钟 / 1 小时 / 每天等），所以统一用一条每分钟触发的任务
 * 按到期时间过滤，而不是每个应用注册一条 Quartz 任务。单次检查会拉取镜像，可能较慢，
 * {@link BaseJob} 自带 {@code @DisallowConcurrentExecution}，上一轮没跑完不会重叠。
 * 日志聚合建议在任务定义上选「按小时」：一天 1440 次执行，按次留记录太多。
 */
@Component
@JobDescription(label = "应用定时更新检查")
public class AppScheduledUpdateJob extends BaseJob {

    @Resource
    private AppService appService;

    @Override
    public String execute(JobDataMap data, Logger logger) {
        progress("开始检查应用定时更新");

        AppService.ScheduledUpdateSummary summary = appService.runDueScheduledUpdates();

        progress("检查完成");
        return summary.describe();
    }
}
