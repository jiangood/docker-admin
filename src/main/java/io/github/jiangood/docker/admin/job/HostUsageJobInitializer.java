package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.framework.config.SystemProperties;
import io.github.jiangood.openadmin.modules.job.entity.JobLogAggregation;
import io.github.jiangood.openadmin.modules.job.entity.SysJob;
import io.github.jiangood.openadmin.modules.job.repository.SysJobRepository;
import io.github.jiangood.openadmin.modules.job.service.SysJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.ObjectAlreadyExistsException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时自动登记「主机用量采集」这条定时任务。
 * <p>
 * 为什么需要：框架的 {@code QuartzInitializer} 只调度 {@code sys_job} 里<b>已存在</b>的行，
 * 没有代码声明式注册。平台是镜像部署，没人会去「定时任务」页面手工新建，所以这里补一次幂等初始化。
 * <ul>
 *   <li>已存在（包括用户停用过、改过 cron）就完全不碰，尊重页面上的配置；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度；</li>
 *   <li>首启时可能和 {@code QuartzInitializer} 抢同一时刻，重复注册抛
 *       {@link ObjectAlreadyExistsException}，忽略即可（不会重复触发）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostUsageJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "主机用量采集";

    /**
     * 每分钟一次：与旧的页面 10 秒轮询相比，主机侧开销降到 1/6 左右。
     */
    private static final String CRON = "0 */1 * * * ?";

    private final SysJobService sysJobService;
    private final SysJobRepository sysJobRepository;
    private final SystemProperties systemProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!systemProperties.isJobEnable()) {
            return;
        }

        String jobClass = HostUsageCollectJob.class.getName();
        boolean exists = sysJobRepository.findAll().stream()
                .anyMatch(job -> jobClass.equals(job.getJobClass()));
        if (exists) {
            return;
        }

        SysJob job = new SysJob();
        job.setName(JOB_NAME);
        job.setCron(CRON);
        job.setEnabled(true);
        job.setJobClass(jobClass);
        // 一分钟一次的采集按每次执行记录会每天多出 1440 条记录与日志文件，聚合成每小时一条
        job.setLogAggregation(JobLogAggregation.HOURLY);

        try {
            sysJobService.save(job, null);
            log.info("已自动创建定时任务「{}」（{}）", JOB_NAME, CRON);
        } catch (ObjectAlreadyExistsException e) {
            log.debug("定时任务「{}」已由 QuartzInitializer 注册，跳过", JOB_NAME);
        } catch (Exception e) {
            log.warn("自动创建定时任务「{}」失败，请到「系统 / 定时任务」手工创建：{}", JOB_NAME, e.toString());
        }
    }
}
