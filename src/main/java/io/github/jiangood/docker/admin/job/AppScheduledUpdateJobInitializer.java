package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时自动登记「应用定时更新检查」这条定时任务（模式同 {@link HostUsageJobInitializer}）。
 * <ul>
 *   <li>已存在且配置过 cron（包括用户停用过、改过 cron）就完全不碰，尊重页面上的配置；</li>
 *   <li>只被框架 {@code JobAutoRegistrar} 登记成空壳（无 cron、默认禁用）时补上默认 cron 并启用；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度；</li>
 *   <li>首启时可能和 {@code QuartzInitializer} 抢同一时刻，重复注册抛
 *       {@link org.quartz.ObjectAlreadyExistsException}，忽略即可。</li>
 * </ul>
 * <p>
 * 任务每分钟触发一次，但只有开启「定时更新」且到期的应用才会真正拉取镜像；
 * 没有应用开启时每轮只是一次配置查询，开销可忽略。
 */
@Component
@RequiredArgsConstructor
public class AppScheduledUpdateJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "应用定时更新检查";

    private static final String CRON = "0 * * * * ?";

    private final BuiltinJobRegistrar registrar;

    @Override
    public void run(ApplicationArguments args) {
        registrar.register(AppScheduledUpdateJob.class.getName(), JOB_NAME, CRON, JobLogAggregation.HOURLY);
    }

}
