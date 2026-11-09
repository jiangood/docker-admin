package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时自动登记「主机用量采集」这条定时任务。
 * <p>
 * 为什么需要：本模块的三个内建任务都应当开箱即用，但框架的 {@code JobAutoRegistrar} 只会按
 * {@code @JobDescription} 把可调度类补建成「无 cron、默认禁用」的空壳行，需要在「定时任务」页面手工配置
 * 才会真正执行。平台是镜像部署，没人会去手工新建，所以这里补一次幂等初始化（详见 {@link BuiltinJobRegistrar}）。
 * <ul>
 *   <li>已存在且配置过 cron（包括用户停用过、改过 cron）就完全不碰，尊重页面上的配置；</li>
 *   <li>只被框架登记成空壳时补上默认 cron 并启用；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度；</li>
 *   <li>首启时可能和 {@code QuartzInitializer} 抢同一时刻，重复注册抛
 *       {@link org.quartz.ObjectAlreadyExistsException}，忽略即可（不会重复触发）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class HostUsageJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "主机用量采集";

    /**
     * 每 10 分钟一次：dockerd 采一次 stats 要等两次采样（约 1 秒），一分钟一轮对主机
     * 和内置 H2 都偏重；10 分钟足够看清趋势，页面仍可随时点「刷新」现采。
     */
    private static final String CRON = "0 */10 * * * ?";

    private final BuiltinJobRegistrar registrar;

    @Override
    public void run(ApplicationArguments args) {
        registrar.register(HostUsageCollectJob.class.getName(), JOB_NAME, CRON, JobLogAggregation.HOURLY);
    }

}
