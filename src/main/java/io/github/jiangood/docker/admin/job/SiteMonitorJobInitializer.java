package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时自动登记「网站可达性检查」这条定时任务（模式同 {@link HostUsageJobInitializer}）。
 * <ul>
 *   <li>已存在且配置过 cron（包括用户停用过、改过 cron）就完全不碰，尊重页面上的配置；</li>
 *   <li>只被框架 {@code JobAutoRegistrar} 登记成空壳（无 cron、默认禁用）时补上默认 cron 并启用；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度；</li>
 *   <li>首启时可能和 {@code QuartzInitializer} 抢同一时刻，重复注册抛
 *       {@link org.quartz.ObjectAlreadyExistsException}，忽略即可。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SiteMonitorJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "网站可达性检查";

    /**
     * 每分钟一次：监控项各自带间隔（1/5/10/30/60 分钟），任务里按到期时间过滤，
     * 绝大多数轮次是空扫（只读一遍监控项配置），开销可忽略。
     */
    private static final String CRON = "0 * * * * ?";

    private final BuiltinJobRegistrar registrar;

    @Override
    public void run(ApplicationArguments args) {
        registrar.register(SiteMonitorCheckJob.class.getName(), JOB_NAME, CRON, JobLogAggregation.HOURLY);
    }

}
