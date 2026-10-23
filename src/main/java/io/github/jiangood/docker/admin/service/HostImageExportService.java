package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.HostImageExportTaskView;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 主机详情页的镜像导出：把主机上已有的镜像用 {@code docker save} 打成 tar，留在主机上供下载。
 * <p>
 * 大镜像不在平台侧中转落盘（SSH 主机直接在主机上打包），下载时才按需读取：
 * SSH 主机走 SFTP 流式转发，本机 / TCP 主机直接读平台文件。tar 不自动删除，
 * 用户可在弹窗里看到保存路径并自行清理。
 * <p>
 * 任务状态只存内存：logId 同时是日志文件名与主机侧目录名，进程重启后任务丢失，
 * 但已生成的 tar 仍在主机上，可按日志里的路径取用。
 */
@Service
public class HostImageExportService {

    private static final Logger fileLog = FileLog.logger();

    /**
     * 镜像标识白名单：镜像 ID（sha256:...）与 repo:tag 都要放行，且不能含空白。
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:\\-]*$");

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }

    /**
     * 一次导出任务。已写入大小与速度由保存过程持续更新，供弹窗展示。
     */
    private static class Task {

        private final String logId;
        private final String hostId;
        private final String hostName;
        private final String image;
        private final long startTime;

        private volatile Status status = Status.RUNNING;
        private volatile String fileName;
        private volatile String path;
        private volatile boolean remote;
        private volatile long size;
        /**
         * 当前写入速度（字节/秒）；成功后改为整次导出的平均速度。
         */
        private volatile long speed;

        Task(String logId, String hostId, String hostName, String image) {
            this.logId = logId;
            this.hostId = hostId;
            this.hostName = hostName;
            this.image = image;
            this.startTime = System.currentTimeMillis();
        }

        void progress(long written, long bytesPerSecond) {
            this.size = written;
            this.speed = bytesPerSecond;
        }

        void complete(ImageDownloadService.TarResult result) {
            this.fileName = result.getFileName();
            this.path = result.getPath();
            this.size = result.getSize();
            this.remote = result.isRemote();
            long elapsed = Math.max(1L, System.currentTimeMillis() - startTime);
            this.speed = result.getSize() * 1000L / elapsed;
            this.status = Status.SUCCESS;
        }

        void fail() {
            this.status = Status.FAILED;
        }

    }

    @Resource
    private TaskLogRegistry taskLogRegistry;

    @Resource
    private ImageDownloadService imageDownloadService;

    /**
     * hostId + image -> 最近一次导出任务；同一镜像重复导出时覆盖旧任务。
     */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /**
     * logId -> 任务，供下载接口按 logId 定位文件。
     */
    private final Map<String, Task> tasksByLogId = new ConcurrentHashMap<>();

    /**
     * 登记一次导出任务并返回 logId。容量、路径等校验留在真正的保存阶段，失败原因写进日志。
     */
    public String register(Host host, String image) {
        String ref = StrUtil.trim(image);
        Assert.hasText(ref, "镜像不能为空");
        Assert.isTrue(IMAGE_PATTERN.matcher(ref).matches(), "镜像标识格式不正确");

        String logId = "host-export-" + RandomUtil.randomString(16);

        Task task = new Task(logId, host.getId(), host.getName(), ref);
        Task old = tasks.put(key(host.getId(), ref), task);
        if (old != null) {
            tasksByLogId.remove(old.logId);
        }
        tasksByLogId.put(logId, task);
        // 在请求线程先标记运行中：前端拿到 logId 后可能立刻连日志 WebSocket，
        // 若等 @Async 线程起来再登记，日志流会误判任务已结束而提前关闭
        taskLogRegistry.start(logId);
        return logId;
    }

    /**
     * 后台执行 docker save。异常只写进导出日志（前端在日志里可见），不向上抛。
     */
    @Async
    public void export(String logId, String hostId, String image) {
        Task task = tasksByLogId.get(logId);
        taskLogRegistry.start(logId);
        FileLog.bind(logId);
        try {
            ImageDownloadService.TarResult result = imageDownloadService.saveToHost(
                    logId, hostId, image, false, task == null ? null : task::progress);
            if (task != null) {
                task.complete(result);
            }
            fileLog.info("导出完成，可以下载了");
        } catch (Exception e) {
            // 面向用户只给一行原因，堆栈留给后端排查
            fileLog.error("镜像导出失败: {}", e.getMessage());
            fileLog.debug("镜像导出失败详情", e);
            if (task != null) {
                task.fail();
            }
        } finally {
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    // ------------------------------------------------------------------ 状态与下载

    /**
     * 主机详情页弹窗用：某主机上某个镜像最近一次的导出状态；从未导出过时返回 null。
     */
    public HostImageExportTaskView taskView(String hostId, String image) {
        return view(tasks.get(key(hostId, StrUtil.trim(image))));
    }

    /**
     * 下载前确认任务已完成，并返回文件名 / 大小等响应头所需信息。
     */
    public HostImageExportTaskView readyTask(String logId) {
        Assert.state(isValidLogId(logId), "非法的导出任务标识");
        Task task = tasksByLogId.get(logId);
        Assert.notNull(task, "导出任务不存在或已失效，请重新导出");
        Assert.state(isReady(task), "导出尚未完成或归档已不存在");
        return view(task);
    }

    /**
     * 把 tar 流式写给浏览器；不删除主机上的源文件。
     */
    public void copyTarTo(String logId, OutputStream out) throws Exception {
        Task task = tasksByLogId.get(logId);
        Assert.notNull(task, "导出任务不存在或已失效");
        Assert.state(isReady(task), "导出尚未完成或归档已不存在");
        imageDownloadService.copyTarTo(task.hostId, task.path, task.remote, out);
    }

    private HostImageExportTaskView view(Task task) {
        if (task == null) {
            return null;
        }
        HostImageExportTaskView view = new HostImageExportTaskView();
        view.setLogId(task.logId);
        view.setImage(task.image);
        view.setHostName(task.hostName);
        view.setFileName(task.fileName);
        view.setPath(task.path);
        view.setRemote(task.remote);
        view.setStatus(task.status.name());
        view.setReady(isReady(task));
        view.setSize(task.size);
        view.setSpeed(task.speed);
        view.setStartTime(task.startTime);
        return view;
    }

    private static boolean isReady(Task task) {
        if (task.status != Status.SUCCESS) {
            return false;
        }
        return task.remote || (task.path != null && new File(task.path).isFile());
    }

    /**
     * logId 同时是日志文件名与主机侧目录名，必须按白名单校验，防止目录穿越。
     */
    private static boolean isValidLogId(String logId) {
        return logId != null && logId.matches("^[A-Za-z0-9_-]{1,64}$");
    }

    private static String key(String hostId, String image) {
        return hostId + "\u0000" + image;
    }

}
