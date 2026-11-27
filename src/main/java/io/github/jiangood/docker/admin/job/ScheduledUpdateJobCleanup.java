package io.github.jiangood.docker.admin.job;

import io.github.jiangood.openadmin.framework.config.SystemProperties;
import io.github.jiangood.openadmin.modules.job.entity.SysJob;
import io.github.jiangood.openadmin.modules.job.repository.SysJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「应用定时更新」功能整体移除后，清理升级前留下的定时任务记录。
 * <p>
 * 框架只会告警「执行类不存在，请手动删除」，这里直接删掉那行记录，
 * 避免每次启动都报错，也避免用户在「定时任务」页面看到一个永远跑不起来的任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledUpdateJobCleanup implements ApplicationRunner {

    /** 已删除的任务类全名，升级前的 sys_job 里可能还留着一行 */
    private static final String REMOVED_JOB_CLASS = "io.github.jiangood.docker.admin.job.AppScheduledUpdateJob";

    private final SysJobRepository sysJobRepository;
    private final SystemProperties systemProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!systemProperties.isJobEnable()) {
            return;
        }
        List<SysJob> jobs = sysJobRepository.findByJobClass(REMOVED_JOB_CLASS);
        if (jobs.isEmpty()) {
            return;
        }
        sysJobRepository.deleteAll(jobs);
        log.info("已清理「应用定时更新检查」遗留任务记录 {} 条", jobs.size());
    }
}
