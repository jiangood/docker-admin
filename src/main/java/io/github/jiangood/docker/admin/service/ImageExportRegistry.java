package io.github.jiangood.docker.admin.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 镜像导出任务登记表：记住「哪个镜像的哪个 tag 最近一次导出」。
 * <p>
 * 临时 tar 的文件名是随机 logId，后端原本无法从 (imageUrl, tag) 反查到文件；
 * 有了这张表，列表页才能逐行展示导出状态（导出中 / 可下载 / 失败），
 * 并且关闭抽屉、刷新页面之后依然找得回上次的导出结果。
 * <p>
 * 纯内存、不落库：导出本身只是临时文件（有 TTL 清扫兜底），进程重启后丢失可接受。
 */
@Component
public class ImageExportRegistry {

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }

    /**
     * 单条导出任务。是否可下载、文件多大不在此处推导，由 {@link ImageExportService} 按文件实际状态给出。
     */
    public static class Task {

        private final String logId;
        private final String imageUrl;
        private final String tag;
        private final long startTime;
        private Status status = Status.RUNNING;

        Task(String logId, String imageUrl, String tag) {
            this.logId = logId;
            this.imageUrl = imageUrl;
            this.tag = tag;
            this.startTime = System.currentTimeMillis();
        }

        public String getLogId() {
            return logId;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public String getTag() {
            return tag;
        }

        public long getStartTime() {
            return startTime;
        }

        public Status getStatus() {
            return status;
        }

        void setStatus(Status status) {
            this.status = status;
        }

    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public static String key(String imageUrl, String tag) {
        return imageUrl + ":" + tag;
    }

    /**
     * 登记新任务。同一 (imageUrl, tag) 重复导出时覆盖旧任务，旧 tar 留给 TTL 清扫。
     */
    public void register(String logId, String imageUrl, String tag) {
        tasks.put(key(imageUrl, tag), new Task(logId, imageUrl, tag));
    }

    /**
     * 任务结束（成功或失败），前端据此把「导出中」翻成「可下载」或「失败」。
     */
    public void finish(String logId, Status status) {
        for (Task task : tasks.values()) {
            if (task.logId.equals(logId)) {
                task.setStatus(status);
                return;
            }
        }
    }

    public void remove(String imageUrl, String tag) {
        tasks.remove(key(imageUrl, tag));
    }

    public Task get(String imageUrl, String tag) {
        return tasks.get(key(imageUrl, tag));
    }

    public Task getByLogId(String logId) {
        if (logId == null) {
            return null;
        }
        for (Task task : tasks.values()) {
            if (logId.equals(task.logId)) {
                return task;
            }
        }
        return null;
    }

    /**
     * 某镜像仓库（或全部，imageUrl 为空时）的导出任务。
     */
    public List<Task> list(String imageUrl) {
        List<Task> list = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (imageUrl == null || imageUrl.equals(task.imageUrl)) {
                list.add(task);
            }
        }
        return list;
    }

    /**
     * 清理已无价值的登记：文件已不在（下载成功或被 TTL 清扫），
     * 或任务早已结束且超过保留时长。
     */
    public void sweep(long maxAgeMillis, Predicate<String> fileMissing) {
        long deadline = System.currentTimeMillis() - maxAgeMillis;
        tasks.values().removeIf(task ->
                fileMissing.test(task.logId)
                        || (task.getStatus() != Status.RUNNING && task.getStartTime() < deadline));
    }

}
