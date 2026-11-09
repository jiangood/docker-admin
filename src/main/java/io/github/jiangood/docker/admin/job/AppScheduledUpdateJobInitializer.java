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
 * 首次启动时自动登记「应用定时更新检查」这条定时任务（模式同 {@link HostUsageJobInitializer}）。
 * <ul>
 *   <li>已存在（包括用户停用过、改过 cron）就完全不碰，尊重页面上的配置；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度；</li>
 *   <li>首启时可能和 {@code QuartzInitializer} 抢同一时刻，重复注册抛
 *       {@link ObjectAlreadyExistsException}，忽略即可。</li>
 * </ul>
 * <p>
 * 任务每分钟触发一次，但只有开启「定时更新」且到期的应用才会真正拉取镜像；
 * 没有应用开启时每轮只是一次配置查询，开销可忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppScheduledUpdateJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "应用定时更新检查";

    private static final String CRON = "0 * * * * ?";

    private final SysJobService sysJobService;
    private final SysJobRepository sysJobRepository;
    private final SystemProperties systemProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!systemProperties.isJobEnable()) {
            return;
        }

        String jobClass = AppScheduledUpdateJob.class.getName();
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
        // 一天 1440 次执行，按次留记录太多，聚合成每小时一条
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
