package io.github.jiangood.docker.admin.service.compose;

import io.github.jiangood.docker.admin.dto.ComposeTaskVo;
import io.github.jiangood.openadmin.util.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compose 命令任务的内存状态（与镜像导出同样风格：不落库）。
 * <p>
 * 同一主机上的同一个项目同时只允许一个任务：{@code down} 与 {@code up} 互相打断的后果
 * 比构建任务严重得多，所以这里直接拒绝第二次触发，而不是"新的覆盖旧的"。
 */
@Component
public class ComposeTaskRegistry {

    /**
     * 一次任务的状态。字段会在异步线程里更新，统一用 volatile。
     */
    public static final class Task {

        String logId;
        String hostId;
        String dir;
        String action;

        volatile String status = "RUNNING";
        volatile Integer exitCode;
        volatile long startTime = System.currentTimeMillis();
        volatile Long endTime;
        volatile String message;
        volatile boolean cancelled;

        public String getLogId() {
            return logId;
        }

        public String getHostId() {
            return hostId;
        }

        public String getDir() {
            return dir;
        }

        public String getAction() {
            return action;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            this.cancelled = true;
            if ("RUNNING".equals(status)) {
                this.message = "取消中…";
            }
        }
    }

    private final Map<String, Task> byLogId = new ConcurrentHashMap<>();

    /** hostId + 项目目录 -> 最近一次任务，供页面刷新后查看状态 */
    private final Map<String, Task> lastByDir = new ConcurrentHashMap<>();

    private final Set<String> runningDirs = ConcurrentHashMap.newKeySet();

    /**
     * 登记一个任务。同一个项目已有任务在跑时直接拒绝。
     */
    public Task start(String hostId, String dir, String action, String logId) {
        String key = key(hostId, dir);
        if (!runningDirs.add(key)) {
            throw new BusinessException("该项目有命令正在执行，请等它结束或先取消");
        }
        Task task = new Task();
        task.logId = logId;
        task.hostId = hostId;
        task.dir = dir;
        task.action = action;
        byLogId.put(logId, task);
        lastByDir.put(key, task);
        return task;
    }

    public Task running(String logId) {
        return logId == null ? null : byLogId.get(logId);
    }

    public void finish(Task task, String status, Integer exitCode, String message) {
        if (task == null) {
            return;
        }
        task.status = status;
        task.exitCode = exitCode;
        task.message = message;
        task.endTime = System.currentTimeMillis();
        runningDirs.remove(key(task.hostId, task.dir));
        byLogId.remove(task.logId);
    }

    /**
     * 某项目最近一次任务；从未执行过时返回 null。
     */
    public ComposeTaskVo last(String hostId, String dir) {
        Task task = lastByDir.get(key(hostId, dir));
        if (task == null) {
            return null;
        }
        ComposeTaskVo vo = new ComposeTaskVo();
        vo.setLogId(task.logId);
        vo.setHostId(task.hostId);
        vo.setDir(task.dir);
        vo.setAction(task.action);
        vo.setStatus(task.status);
        vo.setExitCode(task.exitCode);
        vo.setStartTime(task.startTime);
        vo.setEndTime(task.endTime);
        vo.setMessage(task.message);
        return vo;
    }

    private static String key(String hostId, String dir) {
        return hostId + "\u0000" + dir;
    }
}
