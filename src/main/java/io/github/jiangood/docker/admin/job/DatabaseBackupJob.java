package io.github.jiangood.docker.admin.job;

import io.github.jiangood.docker.admin.service.DatabaseBackupService;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import io.github.jiangood.openadmin.modules.job.JobResult;
import jakarta.annotation.Resource;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 数据库备份：导出业务关键表的 SQL，保留本地副本并发送到系统参数里的默认收件邮箱。
 * <p>
 * 默认每周执行一次（见 {@link DatabaseBackupJobInitializer}），也可以在「系统 - 定时任务」里
 * 改 cron / 临时停用、选择发送邮件使用的代理。{@link BaseJob} 自带
 * {@code @DisallowConcurrentExecution}，大库导出未结束时不会重叠执行。
 */
@Component
@JobDescription(label = "数据库备份", paramsProvider = DatabaseBackupJobParamProvider.class)
public class DatabaseBackupJob extends BaseJob {

    @Resource
    private DatabaseBackupService databaseBackupService;

    @Override
    public JobResult execute(JobDataMap data, Logger logger) {
        String proxyId = data.getString(DatabaseBackupJobParamProvider.PARAM_PROXY_ID);
        String summary = databaseBackupService.backupAndSend(proxyId);
        logger.info(summary);
        return JobResult.ok(summary);
    }
}
