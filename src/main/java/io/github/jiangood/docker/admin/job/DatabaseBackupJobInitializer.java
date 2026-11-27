package io.github.jiangood.docker.admin.job;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时自动登记「数据库备份」定时任务（模式同 {@link BuiltinJobRegistrar}）。
 * <ul>
 *   <li>默认每周一凌晨 3 点执行一次；用户改过 cron 或停用后不再覆盖；</li>
 *   <li>只被框架 {@code JobAutoRegistrar} 登记成空壳（无 cron、默认禁用）时补上默认 cron 并启用；</li>
 *   <li>{@code sys.job-enable=false} 时全局关停，不创建也不调度。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DatabaseBackupJobInitializer implements ApplicationRunner {

    private static final String JOB_NAME = "数据库备份";

    /** Quartz：每周一 03:00。 */
    private static final String CRON = "0 0 3 ? * MON";

    private final BuiltinJobRegistrar registrar;

    @Override
    public void run(ApplicationArguments args) {
        registrar.register(DatabaseBackupJob.class.getName(), JOB_NAME, CRON);
    }

}
