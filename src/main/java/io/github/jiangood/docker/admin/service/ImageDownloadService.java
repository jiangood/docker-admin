package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.jiangood.docker.admin.config.DockerSettings;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadTaskView;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.modules.logviewer.util.TaskLog;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;
import io.github.jiangood.openadmin.util.SpringTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 镜像 tar：读取 docker 的 {@code save} 流，统一保存到平台服务器，网页从平台流式下载。
 * <p>
 * 不再区分连接方式（本机 / TCP / SSH）与是否部署主机辅助容器：三条链路最终都是
 * {@code saveImageCmd} 返回的字节流，由平台写入 {@code {数据目录}/image-tar/{logId}/}。
 * 这样行为可预期、代码只有一条路径，代价是需要平台磁盘容纳整个 tar（另配过期清理）。
 * <p>
 * 结果登记在内存里，网页据此显示「下载 tar」；进程重启后登记丢失，但 tar 仍留在平台上，
 * 超过保留天数（系统参数 {@code docker.image-tar.retain-days}）的目录会被定时清理。
 * 过程写入 {@code {日志目录}/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Service
@Slf4j
public class ImageDownloadService {

    private static final Logger fileLog = TaskLog.logger();

    /**
     * 平台侧 tar 目录名，放在日志目录的上一级（容器部署即 /data/image-tar）。
     */
    private static final String LOCAL_TAR_DIR_NAME = "image-tar";

    /**
     * 内存里最多保留多少个已完成的 tar 登记，超出后淘汰最早的（tar 文件本身不删）。
     */
    private static final int MAX_SAVED_TARS = 50;

    /**
     * 镜像地址白名单：禁止空格与 shell 元字符。
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:\\-]*$");

    /**
     * 每写入多少字节上报一次进度日志。
     */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    /**
     * 过期清理的执行间隔：启动 10 分钟后第一轮，之后每小时一轮。
     */
    private static final long SWEEP_DELAY_MINUTES = 10;

    private static final long SWEEP_INTERVAL_MINUTES = 60;

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    SysTaskLogService taskLogService;

    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerClientManager;

    @Resource
    DockerSettings dockerSettings;

    /**
     * 已生成、可供网页下载的 tar：logId -> 结果。只存内存，平台重启后丢失（tar 仍在平台上）。
     */
    private final Map<String, SavedTar> savedTars = new ConcurrentHashMap<>();

    /**
     * 过期清理线程：只做删目录，不参与任务调度，异常全部吞掉。
     */
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "image-tar-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 一次已完成保存的 tar，logId 同时是目录名。
     */
    private record SavedTar(String fileName, String path, long size, long createdAt) {
    }

    /**
     * 保存 tar 的结果：下载弹窗展示路径、大小。
     */
    public record TarResult(String fileName, String path, long size) {
    }

    /**
     * 镜像 tar 导出进度：已写入字节数与当前写入速度（字节/秒）。
     */
    @FunctionalInterface
    public interface TarProgress {
        void update(long written, long bytesPerSecond);
    }

    @PostConstruct
    void startSweeper() {
        sweeper.scheduleWithFixedDelay(this::sweepQuietly,
                SWEEP_DELAY_MINUTES, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopSweeper() {
        sweeper.shutdownNow();
    }

    /**
     * 写入速度采样：按固定间隔取增量，再做指数平滑，避免速度数字剧烈跳动。
     */
    private static final class SpeedMeter {

        private static final long SAMPLE_INTERVAL_MILLIS = 500;

        private long lastTime = System.currentTimeMillis();
        private long lastBytes;
        private long speed;

        long update(long bytes) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;
            if (elapsed >= SAMPLE_INTERVAL_MILLIS) {
                long delta = Math.max(0L, bytes - lastBytes);
                long instant = delta * 1000L / elapsed;
                speed = speed == 0 ? instant : (speed * 2L + instant) / 3L;
                lastTime = now;
                lastBytes = bytes;
            }
            return speed;
        }

    }

    /**
     * 参数校验：同步执行，让错误直接返回给前端（{@link #download} 是异步方法，异常只会写进日志）。
     */
    public void validate(ImageDownloadRequest p) {
        String image = StrUtil.trim(p.getImage());
        Assert.hasText(image, "请输入镜像地址");
        Assert.isTrue(IMAGE_PATTERN.matcher(image).matches(), "镜像地址格式不正确");
        String hostId = StrUtil.trim(p.getHostId());
        Assert.hasText(hostId, "请选择执行主机");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "执行主机不存在");
    }

    /**
     * 镜像下载页：拉取镜像后打成 tar，并登记结果供网页下载。
     */
    @Async
    public void download(String logId, ImageDownloadRequest p) {
        taskLogRegistry.start(logId);
        TaskLog.bind("task/" + logId);
        boolean success = false;
        String error = null;
        try {
            TarResult result = saveToHost(logId, p.getHostId(), p.getImage(), true, null);
            registerSaved(logId, result);
            fileLog.info("镜像下载完成");
            success = true;
        } catch (Exception e) {
            // 面向用户只给一行原因，堆栈留给后端排查
            error = e.getMessage();
            fileLog.error("镜像下载失败: {}", e.getMessage());
            fileLog.debug("镜像下载失败详情", e);
        } finally {
            // 先写库再结束任务：日志 WS 断开后前端刷新任务列表即可拿到终态
            try {
                taskLogService.finish(logId, success, error);
            } catch (Exception ex) {
                log.warn("写入任务记录失败: logId={} {}", logId, ex.getMessage());
            }
            TaskLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 读取 docker 的 {@code save} 流并保存为平台上的 tar。
     * <p>
     * 调用方需先 {@link TaskLog#bind(String)} 绑定日志文件；本方法只负责保存，
     * 不负责 {@link TaskLogRegistry} 的任务登记与结束。
     *
     * @param pull     是否先从仓库拉取镜像；主机详情页导出已有镜像时为 false
     * @param progress 进度回调（已写入字节数，当前速度），可为 null
     */
    public TarResult saveToHost(String logId, String hostId, String image, boolean pull,
                                TarProgress progress) throws Exception {
        String ref = StrUtil.trim(image);
        Assert.hasText(ref, "请输入镜像地址");
        Assert.isTrue(IMAGE_PATTERN.matcher(ref).matches(), "镜像地址格式不正确");

        Host host = hostService.findById(StrUtil.trim(hostId)).orElse(null);
        Assert.notNull(host, "执行主机不存在");

        DockerClient client = null;
        try {
            fileLog.info(pull ? "开始下载镜像 {}" : "开始导出镜像 {}", ref);
            fileLog.info("执行主机: {} ({})", host.getName(), endpoint(host));

            client = dockerClientManager.getClient(host);
            if (pull) {
                String repo = repoOf(ref);
                String tag = tagOf(ref);
                fileLog.info("拉取镜像 {}:{}", repo, tag);
                client.pullImageCmd(repo)
                        .withTag(tag)
                        .exec(new DefaultCallback<PullResponseItem>("task/" + logId))
                        .awaitCompletion();
                fileLog.info("拉取完成 {}:{}", repo, tag);
            }

            return saveOnPlatform(client, ref, logId, resolveFileName(client, ref), host, progress);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 把 tar 流式写给调用方：平台上的文件直接读盘，不删除源文件。
     */
    public void copyTarTo(String logId, OutputStream out) throws Exception {
        Assert.hasText(logId, "logId 不能为空");
        SavedTar saved = savedTars.get(logId);
        Assert.notNull(saved, "任务不存在或已失效，请重新下载镜像");
        copyFile(saved.path(), out);
    }

    /**
     * 把平台上的 tar 文件写给调用方（主机详情页的导出也复用这里）。
     */
    public void copyFile(String path, OutputStream out) throws Exception {
        File file = new File(path);
        Assert.state(file.isFile(), "tar 文件已不存在: " + path);
        try (InputStream in = Files.newInputStream(file.toPath())) {
            in.transferTo(out);
            out.flush();
        }
    }

    private TarResult saveOnPlatform(DockerClient client, String ref, String logId, String fileName,
                                     Host host, TarProgress progress) throws Exception {
        File dir = localTarDir(logId);
        Assert.state(dir.isDirectory() || dir.mkdirs(), "tar 目录不可用: " + dir.getAbsolutePath());
        File target = new File(dir, fileName);

        if (!host.isLocalDocker()) {
            fileLog.info("远程主机的 docker API 无法写主机文件，tar 将保存到平台服务器，下载时从平台读取");
        }
        fileLog.info("生成 tar 包（docker save）…");

        long written = 0;
        long lastReport = 0;
        SpeedMeter meter = new SpeedMeter();
        reportProgress(progress, 0L, 0L);
        try (InputStream in = client.saveImageCmd(ref).exec();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                // 进度回调每个分片都更新，弹窗里的已写入 / 速度才会平滑；日志仍按 10MB 节流
                long speed = meter.update(written);
                reportProgress(progress, written, speed);
                if (written - lastReport >= PROGRESS_STEP_BYTES) {
                    fileLog.info("已写入 {}{}…", FileUtil.readableFileSize(written), speedSuffix(speed));
                    lastReport = written;
                }
            }
        } catch (Exception e) {
            // 失败时清掉半截文件，避免用户误用
            FileUtil.del(target);
            throw e;
        }

        long speed = meter.update(written);
        reportProgress(progress, written, speed);
        fileLog.info("tar 包已保存：{}（{}）", target.getAbsolutePath(), FileUtil.readableFileSize(written));
        return new TarResult(fileName, target.getAbsolutePath(), written);
    }

    // ------------------------------------------------------------------ 任务登记 / 网页下载

    /**
     * 登记已完成的 tar，供镜像下载页显示下载按钮；超过容量时淘汰最早的登记（不删文件）。
     */
    private void registerSaved(String logId, TarResult result) {
        long now = System.currentTimeMillis();
        if (savedTars.size() >= MAX_SAVED_TARS) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, SavedTar> entry : savedTars.entrySet()) {
                if (entry.getValue().createdAt() < oldestAt) {
                    oldestAt = entry.getValue().createdAt();
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) {
                savedTars.remove(oldest);
            }
        }
        savedTars.put(logId, new SavedTar(result.fileName(), result.path(), result.size(), now));
    }

    /**
     * 镜像下载页展示用：已完成的 tar 信息；任务尚未结束或登记已丢失时返回 null。
     */
    public ImageDownloadTaskView taskView(String logId) {
        SavedTar saved = logId == null ? null : savedTars.get(logId);
        if (saved == null) {
            return null;
        }
        ImageDownloadTaskView view = new ImageDownloadTaskView();
        view.setLogId(logId);
        view.setFileName(saved.fileName());
        view.setPath(saved.path());
        view.setSize(saved.size());
        view.setReady(true);
        return view;
    }

    /**
     * 下载前确认任务存在，并返回文件名 / 大小等响应头所需信息。
     */
    public ImageDownloadTaskView readyTask(String logId) {
        Assert.hasText(logId, "logId 不能为空");
        SavedTar saved = savedTars.get(logId);
        Assert.notNull(saved, "任务不存在或已失效，请重新下载镜像");
        return taskView(logId);
    }

    /**
     * 删除已生成的 tar（连同登记），给用户腾磁盘的入口；删除失败时抛出让前端提示。
     */
    public void deleteTar(String logId) {
        Assert.hasText(logId, "logId 不能为空");
        Assert.notNull(savedTars.get(logId), "任务不存在或已失效");
        FileUtil.del(localTarDir(logId));
        savedTars.remove(logId);
    }

    /**
     * 下载完成后自动删除：失败只记日志，不影响已经写完的响应。
     */
    public void deleteTarQuietly(String logId) {
        try {
            deleteTar(logId);
        } catch (Exception e) {
            log.warn("下载后删除 tar 失败: logId={} {}", logId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 过期清理

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("清理过期镜像 tar 失败: {}", e.getMessage());
        }
    }

    /**
     * 删除超过保留天数的 tar 目录：目录名就是 logId，同时清掉内存登记。
     * 正在写入的目录不会被命中——文件名每次都新建在独立的 logId 目录里。
     */
    void sweep() {
        File[] dirs = tarRoot().listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return;
        }
        int days = dockerSettings.imageTarRetainDays();
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        for (File dir : dirs) {
            if (latestTouched(dir) >= cutoff) {
                continue;
            }
            FileUtil.del(dir);
            savedTars.remove(dir.getName());
            log.info("清理过期镜像 tar：{}（保留 {} 天）", dir.getName(), days);
        }
    }

    /**
     * 目录内最新的写入时间：写入文件内容不刷新目录 mtime，所以取目录与文件的较大值。
     */
    private static long latestTouched(File dir) {
        long latest = dir.lastModified();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                latest = Math.max(latest, file.lastModified());
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 平台侧 tar 根目录：放在日志目录的上一级，容器部署时即挂载出来的 /data/image-tar。
     */
    private static File tarRoot() {
        String logPath = SpringTool.getProperty("logging.file.path");
        File base = StrUtil.isBlank(logPath)
                ? new File("/data")
                : new File(logPath).getAbsoluteFile().getParentFile();
        if (base == null) {
            base = new File(".");
        }
        return new File(base, LOCAL_TAR_DIR_NAME);
    }

    /**
     * 单次任务的 tar 目录，目录名就是 logId。
     */
    private static File localTarDir(String logId) {
        return new File(tarRoot(), logId);
    }

    /**
     * 镜像去掉 tag 后的仓库名；registry 带端口时也不会误判。
     */
    private static String repoOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(0, lastColon) : image;
    }

    /**
     * 镜像 tag，未写时默认 latest。
     */
    private static String tagOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(lastColon + 1) : "latest";
    }

    /**
     * 文件名优先取镜像的第一个 repo tag（如 nginx-1.27-alpine.tar），
     * 无标签镜像退化为 image-{短 ID}.tar。
     */
    private static String resolveFileName(DockerClient client, String ref) {
        try {
            InspectImageResponse response = client.inspectImageCmd(ref).exec();
            var tags = response.getRepoTags();
            if (tags != null) {
                for (String tag : tags) {
                    if (StrUtil.isNotBlank(tag) && !"<none>:<none>".equals(tag)) {
                        return safeFileName(tag) + ".tar";
                    }
                }
            }
        } catch (Exception e) {
            fileLog.debug("探测镜像 {} 的标签失败：{}", ref, e.getMessage());
        }
        return "image-" + shortId(ref) + ".tar";
    }

    /**
     * 去掉注册中心前缀，并把 : / @ 等文件名非法字符换掉。
     */
    private static String safeFileName(String ref) {
        String name = ref;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        name = name.replace(':', '-').replace('@', '-')
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return StrUtil.blankToDefault(name, "image");
    }

    private static String shortId(String ref) {
        String s = ref.startsWith("sha256:") ? ref.substring(7) : ref;
        s = s.replaceAll("[^A-Za-z0-9]", "");
        return s.length() > 12 ? s.substring(0, 12) : StrUtil.blankToDefault(s, "image");
    }

    private static void reportProgress(TarProgress progress, long written, long bytesPerSecond) {
        if (progress != null) {
            progress.update(written, bytesPerSecond);
        }
    }

    /**
     * 日志里的速度后缀：同时给每秒与每分钟，例如 80 MB/s（4.7 GB/min）。
     * 速度还没算出来时不显示，避免首行日志出现 0 B/s。
     */
    private static String speedSuffix(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "";
        }
        return "，" + FileUtil.readableFileSize(bytesPerSecond) + "/s（"
                + FileUtil.readableFileSize(bytesPerSecond * 60) + "/min）";
    }

    private static String endpoint(Host host) {
        if (host.isSsh()) {
            String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
            return "ssh://" + StrUtil.blankToDefault(host.getSshUser(), "root") + "@" + sshHost;
        }
        return StrUtil.blankToDefault(host.getDockerHost(), "本机");
    }

}
