package io.github.jiangood.docker.admin.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 镜像导入任务登记表：前端据此判断上传后的后台任务是否结束、导入结果如何。
 * <p>
 * 与 {@link ImageExportRegistry} 不同，导入以 logId 为主键：tar 内的镜像名要载入后才知道，
 * 无法像导出那样用 (imageUrl, tag) 定位任务。
 * <p>
 * 纯内存、不落库：导入过程本身有实时日志，进程重启后任务丢失可接受。
 */
@Component
public class ImageImportRegistry {

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }

    @Getter
    public static class Task {

        private final String logId;
        private final long startTime = System.currentTimeMillis();
        private Status status = Status.RUNNING;

        /**
         * 结束时的提示：成功时是导入结果摘要，失败时是失败原因。
         */
        private String message;

        /**
         * 导入成功的完整镜像地址，形如 registry/namespace/name:tag。
         */
        private List<String> images = new ArrayList<>();

        Task(String logId) {
            this.logId = logId;
        }

        void finish(Status status, String message, List<String> images) {
            this.status = status;
            this.message = message;
            this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        }

    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public void register(String logId) {
        tasks.put(logId, new Task(logId));
    }

    public void finish(String logId, Status status, String message, List<String> images) {
        Task task = tasks.get(logId);
        if (task != null) {
            task.finish(status, message, images);
        }
    }

    public Task get(String logId) {
        return logId == null ? null : tasks.get(logId);
    }

    /**
     * 清理早已结束的任务，避免内存随导入次数无限增长。
     */
    public void sweep(long maxAgeMillis) {
        long deadline = System.currentTimeMillis() - maxAgeMillis;
        tasks.values().removeIf(task -> task.getStatus() != Status.RUNNING && task.getStartTime() < deadline);
    }

}
