package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.TaskLogRepository;
import io.github.jiangood.docker.admin.entity.TaskLog;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台任务记录：任务开始时落库（status=RUNNING），结束时补全结果。
 * <p>
 * 记录由 {@link LongTaskRunner} 统一写入，业务代码一般不直接使用本类。
 * 与 {@link ImageSyncLogService} 相同的写入约定：写库发生在 {@code @Async} 任务线程里，
 * 每个方法自带事务、立即 flush，任务列表随即可见。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskLogService extends BaseService<TaskLog> {

    /**
     * 失败原因过长时截断，避免超出列长度导致记录写不进去（任务结果本身更重要）。
     */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TaskLogRepository taskLogRepository;

    /**
     * 当前触发人（登录用户名）；定时任务等非请求线程返回 SYSTEM。
     */
    public String currentTriggerUser() {
        try {
            io.github.jiangood.openadmin.framework.config.security.LoginUser user =
                    io.github.jiangood.openadmin.framework.auth.LoginTool.getUser();
            return user == null ? "SYSTEM" : user.getUsername();
        } catch (Exception e) {
            return "SYSTEM";
        }
    }

    /**
     * 按 logId 查任务记录（任务取消、日志鉴权用）。
     */
    public TaskLog findByLogId(String logId) {
        return taskLogRepository.findByLogId(logId).orElse(null);
    }

    /**
     * 开始一个任务：先落库 RUNNING 记录，接口返回前端即可打开实时日志。
     */
    @Transactional
    public TaskLog start(String logId, String type, String title, String permission, String triggerUser) {
        TaskLog task = new TaskLog();
        task.setLogId(logId);
        task.setType(type);
        task.setTitle(StrUtil.sub(title, 0, 200));
        task.setStatus(TaskLog.STATUS_RUNNING);
        task.setPermission(permission);
        task.setTriggerUser(StrUtil.sub(triggerUser, 0, 64));
        return taskLogRepository.saveAndFlush(task);
    }

    /**
     * 结束一个任务：写入结果与耗时。任务不存在或已结束时不处理。
     * <p>
     * 必须先写库再调 {@code TaskLogRegistry#finish}：日志 WS 检测到任务结束后关闭连接，
     * 前端刷新列表要能直接拿到终态。
     */
    @Transactional
    public void finish(String logId, boolean success, String errorMessage) {
        TaskLog task = taskLogRepository.findByLogId(logId).orElse(null);
        if (task == null || !task.isRunning()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(success ? TaskLog.STATUS_SUCCESS : TaskLog.STATUS_FAILED);
        task.setEndTime(now);
        if (task.getCreateTime() != null) {
            task.setTimeSpend(Duration.between(task.getCreateTime(), now).toMillis());
        }
        if (!success) {
            task.setError(StrUtil.sub(StrUtil.blankToDefault(errorMessage, "任务失败"), 0, MAX_ERROR_LENGTH));
        }
        taskLogRepository.saveAndFlush(task);
    }

    /**
     * 更新进度快照 JSON。失败只记 debug：进度展示是锦上添花，不能影响任务本身。
     */
    @Transactional
    public void progress(String logId, String progressJson) {
        try {
            taskLogRepository.findByLogId(logId).ifPresent(task -> {
                if (task.isRunning()) {
                    task.setProgress(progressJson);
                    taskLogRepository.save(task);
                }
            });
        } catch (Exception e) {
            log.debug("写入任务进度失败: logId={} {}", logId, e.getMessage());
        }
    }

    /**
     * 平台重启清扫：进程内正在跑的任务都已消失，把遗留 RUNNING 行标记为失败。
     * 日志文件还在，用户仍可通过任务中心回看中断前的输出。
     */
    @Transactional
    public int markInterrupted() {
        List<TaskLog> running = findAll(
                Spec.<TaskLog>of().eq(TaskLog.Fields.status, TaskLog.STATUS_RUNNING),
                Sort.unsorted());
        LocalDateTime now = LocalDateTime.now();
        for (TaskLog task : running) {
            task.setStatus(TaskLog.STATUS_FAILED);
            task.setEndTime(now);
            if (task.getCreateTime() != null) {
                task.setTimeSpend(Duration.between(task.getCreateTime(), now).toMillis());
            }
            task.setError("平台重启，任务中断");
            taskLogRepository.save(task);
        }
        if (!running.isEmpty()) {
            log.info("清扫平台重启遗留的运行中任务记录 {} 条", running.size());
        }
        return running.size();
    }
}
