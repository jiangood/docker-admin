package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.modules.job.entity.SysJob;
import io.github.jiangood.openadmin.modules.job.repository.SysJobRepository;
import io.github.jiangood.openadmin.modules.job.service.SysJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 清理已下线的「主机用量采集」定时任务。
 * <p>
 * 别人手里已有的库仍然留着 {@code sys_job} 里那条记录与对应的 Quartz 触发器，
 * 而任务类已经随功能一起删除，重启后调度会因找不到类而报错。这里在启动时删掉这条残留：
 * 放在 {@link CommandLineRunner} 且用最高优先级，早于框架的 {@code QuartzInitializer}
 * （无优先级，最后执行），因此不会再被重新调度。
 * <p>
 * 只匹配本项目的旧任务类，不影响页面上其他任务。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RemovedUsageJobCleanup implements CommandLineRunner {

    /** 已删除的任务类：只在历史库的 sys_job 记录里还会出现。 */
    private static final String REMOVED_JOB_CLASS = "io.github.jiangood.docker.admin.job.HostUsageCollectJob";

    private final SysJobRepository sysJobRepository;
    private final SysJobService sysJobService;

    @Override
    public void run(String... args) {
        List<SysJob> jobs;
        try {
            jobs = sysJobRepository.findByJobClass(REMOVED_JOB_CLASS);
        } catch (Exception e) {
            log.warn("检查下线的定时任务失败，跳过：{}", e.toString());
            return;
        }
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        for (SysJob job : jobs) {
            try {
                sysJobService.deleteJob(job.getId());
                log.info("已删除下线的定时任务「{}」", job.getName());
            } catch (Exception e) {
                log.warn("删除下线的定时任务「{}」失败：{}", job.getName(), e.toString());
                try {
                    sysJobRepository.deleteById(job.getId());
                } catch (Exception ignored) {
                    // 数据库清理失败也不阻塞启动，页面里已看不到对应任务类
                }
            }
        }
    }

}
