package io.github.jiangood.docker.admin;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import io.github.jiangood.docker.base.tool.GitTool;
import io.github.jiangood.openadmin.modules.job.BaseJob;
import io.github.jiangood.openadmin.modules.job.JobDescription;
import io.github.jiangood.openadmin.util.field.FieldDescription;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 清理代码克隆根目录 {@link GitTool#CLONE_BASE_DIR} 下的历史工作目录。
 * <p>
 * 克隆目录位于系统临时目录（容器内 /tmp），正常构建结束即被删除（见
 * {@code ProjectService#buildImageJob}），本任务只作为兜底：进程被强杀、容器异常退出等
 * 未走到删除逻辑时，清理这里残留的目录，避免磁盘占满。仅删除最后修改时间超过保留天数的目录，
 * 正在构建的目录不受影响。
 */
@Component
@JobDescription(label = "清理代码目录", params = {
        @FieldDescription(name = "retainDays", label = "保留天数", required = true, defaultValue = "1",
                placeholder = "最后修改时间超过该天数的代码目录将被删除")
})
public class CleanGitCodeJob extends BaseJob {

    /** 保留天数，任务参数未配置时的默认值。 */
    private static final int DEFAULT_RETAIN_DAYS = 1;

    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    @Override
    public String execute(JobDataMap data, Logger log) throws Exception {
        int retainDays = Convert.toInt(data.get("retainDays"), DEFAULT_RETAIN_DAYS);
        if (retainDays < 1) {
            throw new IllegalArgumentException("保留天数必须大于 0");
        }
        long expireTime = System.currentTimeMillis() - retainDays * ONE_DAY_MILLIS;

        File baseDir = new File(GitTool.CLONE_BASE_DIR);
        File[] repoDirs = baseDir.listFiles(File::isDirectory);
        if (repoDirs == null || repoDirs.length == 0) {
            log.info("代码目录 {} 不存在或为空，无需清理", baseDir.getAbsolutePath());
            return "OK";
        }

        log.info("开始清理代码目录 {}，保留 {} 天内的目录", baseDir.getAbsolutePath(), retainDays);

        int deletedDirs = 0;
        long releasedBytes = 0;
        for (File repoDir : repoDirs) {
            File[] workDirs = repoDir.listFiles(File::isDirectory);
            if (workDirs != null) {
                for (File workDir : workDirs) {
                    if (workDir.lastModified() >= expireTime) {
                        continue;
                    }
                    long size = FileUtil.size(workDir);
                    if (FileUtil.del(workDir)) {
                        deletedDirs++;
                        releasedBytes += size;
                        log.info("已删除过期代码目录 {}（{}）", workDir.getAbsolutePath(), FileUtil.readableFileSize(size));
                    } else {
                        log.warn("删除代码目录失败 {}", workDir.getAbsolutePath());
                    }
                }
            }
            // 仓库名下已无工作目录时一并删除，避免残留空目录
            File[] remains = repoDir.listFiles();
            if (remains != null && remains.length == 0) {
                FileUtil.del(repoDir);
            }
        }

        log.info("代码目录清理完毕，共删除 {} 个目录，回收 {}",
                deletedDirs, FileUtil.readableFileSize(releasedBytes));
        return "OK";
    }
}
