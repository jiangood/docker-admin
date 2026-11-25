package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import io.github.jiangood.docker.admin.dto.HostAgentVo;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadTaskView;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.logviewer.util.TaskLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.SpringTool;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;

/**
 * 主机镜像 tar：在指定主机上执行 {@code docker save}，把镜像保存成 tar 包。
 * <p>
 * 三种执行方式，按可用性自动选择：
 * <ul>
 *   <li>主机辅助容器（优先）：在 {@code docker-admin-agent} 里执行 {@code docker save}，tar 落在挂载目录的
 *       {@code image-tar/{logId}/} 下，网页可直接下载，也不受主机连接方式（SSH / TCP / 本机）影响；</li>
 *   <li>SSH 主机（未部署辅助容器时）：通过 SSH 执行远端 {@code docker save}，落到
 *       {@code /tmp/docker-admin-image-tar/{logId}/}；</li>
 *   <li>本机 / TCP 主机（未部署辅助容器时）：平台读取 save 流写到 {@code {数据目录}/image-tar/{logId}/}。</li>
 * </ul>
 * 任务完成后结果登记在内存里，网页据此显示「下载 tar」；进程重启后登记丢失，但 tar 仍留在主机 / 平台上。
 * 过程写入 {@code {日志目录}/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageDownloadService {

    private static final Logger fileLog = TaskLog.logger();

    /**
     * SSH 主机上的 tar 存放目录。
     */
    private static final String REMOTE_TAR_DIR = "/tmp/docker-admin-image-tar";

    /**
     * 平台侧 tar 目录名，放在日志目录的上一级（容器部署即 /data/image-tar）。
     */
    private static final String LOCAL_TAR_DIR_NAME = "image-tar";

    /**
     * 辅助容器里存放 tar 的相对目录（相对挂载目录），例如 {@code image-tar/image-download-xxxx/}。
     */
    private static final String AGENT_TAR_DIR_NAME = "image-tar";

    /**
     * 内存里最多保留多少个已完成的 tar 登记，超出后淘汰最早的（tar 文件本身不删）。
     */
    private static final int MAX_SAVED_TARS = 50;

    /**
     * 镜像地址白名单：禁止空格与 shell 元字符，SSH 主机上会拼进 docker 命令。
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:\\-]*$");

    /**
     * 每写入多少字节上报一次进度日志。
     */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    private static final int SSH_TIMEOUT_MILLIS = 10_000;

    /**
     * 辅助容器打包时，写入大小长时间不增长即认为卡住（docker save 不再产出数据）。
     */
    private static final long STALL_TIMEOUT_MILLIS = 15L * 60 * 1000;

    private static final String DEFAULT_SSH_USER = "root";

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    SysTaskLogService taskLogService;

    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerClientManager;

    @Resource
    HostAgentService hostAgentService;

    @Resource
    ContainerService containerService;

    /**
     * 已生成、可供网页下载的 tar：logId -> 结果。只存内存，平台重启后丢失（tar 仍在主机 / 平台上）。
     */
    private final Map<String, SavedTar> savedTars = new ConcurrentHashMap<>();

    /**
     * 一次已完成保存的 tar，{@link #relativePath} 仅辅助容器方式有值（相对挂载目录）。
     */
    private record SavedTar(String hostId, String fileName, String path, String relativePath,
                            long size, Mode mode, long createdAt) {
    }

    /**
     * tar 的保存方式：辅助容器挂载目录 / SSH 主机远端 / 平台服务器。
     */
    public enum Mode {
        AGENT, SSH, PLATFORM
    }

    /**
     * 在主机上生成 tar 的结果：下载弹窗展示路径、大小，并据此决定下载方式。
     */
    public static class TarResult {

        private final String fileName;
        private final String path;
        private final long size;
        private final boolean remote;
        private final Mode mode;
        /**
         * 辅助容器方式下相对挂载目录的路径（如 image-tar/logId/xxx.tar），其他方式为 null。
         */
        private final String relativePath;

        public TarResult(String fileName, String path, long size, boolean remote, Mode mode, String relativePath) {
            this.fileName = fileName;
            this.path = path;
            this.size = size;
            this.remote = remote;
            this.mode = mode;
            this.relativePath = relativePath;
        }

        public String getFileName() {
            return fileName;
        }

        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        public boolean isRemote() {
            return remote;
        }

        public Mode getMode() {
            return mode;
        }

        public String getRelativePath() {
            return relativePath;
        }

    }

    /**
     * 主机镜像 tar 导出进度：已写入字节数与当前写入速度（字节/秒）。
     */
    @FunctionalInterface
    public interface TarProgress {
        void update(long written, long bytesPerSecond);
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
            TarResult result = saveToHost(logId, p.getHostId(), p.getImage(), true, true, null);
            registerSaved(logId, p.getHostId(), result);
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
     * 在主机上执行 {@code docker save} 并保存 tar。
     * <p>
     * 调用方需先 {@link TaskLog#bind(String)} 绑定日志文件；本方法只负责保存，
     * 不负责 {@link TaskLogRegistry} 的任务登记与结束。
     *
     * @param pull        是否先从仓库拉取镜像；主机详情页导出已有镜像时为 false
     * @param preferAgent 是否优先用主机辅助容器打包（镜像下载页为 true，主机详情导出保持原行为）
     * @param progress    进度回调（已写入字节数，当前速度），可为 null
     */
    public TarResult saveToHost(String logId, String hostId, String image, boolean pull,
                                boolean preferAgent, TarProgress progress) throws Exception {
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

            String fileName = resolveFileName(client, ref);

            if (preferAgent) {
                HostAgentService.AgentContext context = agentContext(host);
                if (context != null) {
                    return saveInAgent(host, context, ref, logId, fileName, progress);
                }
            }
            if (host.isSsh()) {
                return saveOnSshHost(host, ref, logId, fileName, progress);
            }
            return saveOnPlatform(client, ref, logId, fileName, host, progress);
        } finally {
            IOUtils.closeQuietly(client);
        }
    }

    /**
     * 把已生成的 tar 流式写给调用方：SSH 主机走 SFTP，本机 / TCP 主机直接读平台文件。
     * <p>
     * 不删除源文件——主机上的 tar 是用户显式导出的，保留在主机上供其自行取用或清理。
     */
    public void copyTarTo(String hostId, String path, boolean remote, OutputStream out) throws Exception {
        if (!remote) {
            File file = new File(path);
            Assert.state(file.isFile(), "tar 文件已不存在: " + path);
            try (InputStream in = Files.newInputStream(file.toPath())) {
                in.transferTo(out);
                out.flush();
            }
            return;
        }

        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "执行主机不存在");

        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = openSshSession(host);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(SSH_TIMEOUT_MILLIS);

            SftpATTRS attrs = sftp.lstat(path);
            Assert.notNull(attrs, "tar 文件已不存在: " + path);
            try (InputStream in = sftp.get(path)) {
                in.transferTo(out);
                out.flush();
            }
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ 保存 tar

    /**
     * SSH 主机：在远端执行 {@code docker save > 文件}，期间轮询文件大小上报进度。
     */
    private TarResult saveOnSshHost(Host host, String ref, String logId, String fileName,
                                    TarProgress progress) throws Exception {
        Session session = null;
        ChannelExec channel = null;
        ChannelSftp sftp = null;
        try {
            session = openSshSession(host);
            String dir = REMOTE_TAR_DIR + "/" + logId;
            String path = dir + "/" + fileName;

            fileLog.info("生成 tar 包（远端 docker save）…");
            fileLog.info("目标路径: {}", path);

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            // mkdir 与 save 合成一条命令：目录创建失败时同样能拿到非 0 退出码
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("mkdir -p " + shellQuote(dir) + " && " + additionalFileToSourcePrefix()
                    + "docker save " + shellQuote(ref) + " > " + shellQuote(path));
            channel.setInputStream(null);
            channel.setErrStream(err);
            channel.connect(SSH_TIMEOUT_MILLIS);

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(SSH_TIMEOUT_MILLIS);

            long lastSize = -1;
            long lastLogAt = System.currentTimeMillis();
            SpeedMeter meter = new SpeedMeter();
            while (!channel.isClosed()) {
                Thread.sleep(500);
                long size = remoteSize(sftp, path);
                long speed = meter.update(size);
                // 每轮都上报，卡住时速度会自然衰减为 0，前端能看出“没在写”
                reportProgress(progress, size, speed);

                long now = System.currentTimeMillis();
                if (size != lastSize && now - lastLogAt >= 2000) {
                    fileLog.info("已写入 {}{}…", FileUtil.readableFileSize(size), speedSuffix(speed));
                    lastSize = size;
                    lastLogAt = now;
                }
            }

            int status = channel.getExitStatus();
            if (status != 0) {
                // 失败时 docker save 可能已写出半截文件，清掉避免用户误用
                execQuietly(session, "rm -f " + shellQuote(path));
                String message = StrUtil.trim(err.toString(StandardCharsets.UTF_8));
                throw new IllegalStateException("远端 docker save 失败（退出码 " + status + "）"
                        + (StrUtil.isBlank(message) ? "" : "：" + message));
            }

            long size = remoteSize(sftp, path);
            long speed = meter.update(size);
            reportProgress(progress, size, speed);
            fileLog.info("tar 包已保存：{}（{}）", path, FileUtil.readableFileSize(size));
            return new TarResult(fileName, path, size, true, Mode.SSH, null);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    /**
     * 本机主机：直接读 docker 的 save 流写到平台数据目录。
     * <p>
     * TCP 远程主机的 docker API 无法写主机文件，只能落在这里，日志中明确告知用户。
     */
    private TarResult saveOnPlatform(DockerClient client, String ref, String logId, String fileName,
                                     Host host, TarProgress progress) throws Exception {
        boolean remote = !isLocalHost(host);
        File dir = localTarDir(logId);
        Assert.state(dir.isDirectory() || dir.mkdirs(), "tar 目录不可用: " + dir.getAbsolutePath());
        File target = new File(dir, fileName);

        if (remote) {
            fileLog.warn("该主机为 TCP 连接方式，docker API 无法直接写远端主机文件，tar 将保存到平台服务器");
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
        return new TarResult(fileName, target.getAbsolutePath(), written, false, Mode.PLATFORM, null);
    }

    /**
     * 辅助容器方式：在 {@code docker-admin-agent} 里执行 {@code docker save}，tar 写进挂载目录的
     * {@code image-tar/{logId}/} 下，下载时经 Docker archive 接口流式读取。
     * <p>
     * 命令放后台执行（输出重定向到文件），本线程按固定间隔读文件大小与退出码，
     * 这样既能上报进度，也不用长时间占用一条 docker exec 连接。
     */
    private TarResult saveInAgent(Host host, HostAgentService.AgentContext context, String ref,
                                  String logId, String fileName, TarProgress progress) throws Exception {
        String relativeDir = AGENT_TAR_DIR_NAME + "/" + logId;
        String relativePath = relativeDir + "/" + fileName;
        String dir = HostRootPolicy.toAgentPath(relativeDir);
        String tarPath = HostRootPolicy.toAgentPath(relativePath);
        String exitPath = dir + "/.save.exit";
        String errPath = dir + "/.save.err";
        String hostPath = StrUtil.removeSuffix(context.root(), "/") + "/" + relativePath;

        fileLog.info("使用主机辅助容器打包（{}）…", hostPath);

        // 建目录并清掉上一次同 logId 的残留（logId 随机，正常不会命中）
        execOrThrow(host, context, "mkdir -p " + shellQuote(dir)
                + " && rm -f " + shellQuote(tarPath) + " " + shellQuote(exitPath) + " " + shellQuote(errPath));

        String save = "docker save " + shellQuote(ref) + " > " + shellQuote(tarPath)
                + " 2> " + shellQuote(errPath) + "; echo $? > " + shellQuote(exitPath);
        // 放后台执行：外层 sh 立即退出，docker exec 连接随之释放，save 由容器内的子进程继续
        containerService.exec(host, context.containerId(), List.of("sh", "-c",
                "(" + save + ") > /dev/null 2>&1 &"));

        long lastSize = -1;
        long lastSeenSize = -1;
        long lastGrowthAt = System.currentTimeMillis();
        long lastLogAt = System.currentTimeMillis();
        SpeedMeter meter = new SpeedMeter();
        while (true) {
            // 轮询间隔 2 秒：SSH 连接的主机每次 exec 都要新建会话，太频繁会拖慢主机
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                removeAgentDir(host, context, dir);
                throw new IllegalStateException("任务已取消", e);
            }
            long[] state = readAgentSaveState(host, context, exitPath, tarPath);
            long size = state[0];
            long code = state[1];
            long speed = meter.update(size);
            reportProgress(progress, size, speed);

            long now = System.currentTimeMillis();
            if (size > lastSeenSize) {
                lastSeenSize = size;
                lastGrowthAt = now;
            }
            if (size != lastSize && now - lastLogAt >= 2000) {
                fileLog.info("已写入 {}{}…", FileUtil.readableFileSize(size), speedSuffix(speed));
                lastSize = size;
                lastLogAt = now;
            }

            if (code >= 0) {
                if (code != 0) {
                    String message = readAgentText(host, context, errPath);
                    removeAgentDir(host, context, dir);
                    throw new IllegalStateException("辅助容器内 docker save 失败（退出码 " + code + "）"
                            + (StrUtil.isBlank(message) ? "" : "：" + message));
                }
                break;
            }
            if (now - lastGrowthAt > STALL_TIMEOUT_MILLIS) {
                removeAgentDir(host, context, dir);
                throw new IllegalStateException("辅助容器内 docker save 长时间没有写入数据（"
                        + (STALL_TIMEOUT_MILLIS / 60_000) + " 分钟），已终止；请检查主机磁盘空间与 docker 服务状态");
            }
        }

        long[] state = readAgentSaveState(host, context, exitPath, tarPath);
        long size = state[0];
        long speed = meter.update(size);
        reportProgress(progress, size, speed);
        // 只留 tar，去掉两个辅助文件
        execQuietly(host, context, "rm -f " + shellQuote(exitPath) + " " + shellQuote(errPath));
        fileLog.info("tar 包已保存：{}（{}）", hostPath, FileUtil.readableFileSize(size));
        fileLog.info("可在本页直接下载，或在该主机的终端里访问上面的路径");
        return new TarResult(fileName, hostPath, size, true, Mode.AGENT, relativePath);
    }

    /**
     * 读取后台 {@code docker save} 的状态：文件大小与退出码（未结束为 -1）。
     * 一次 exec 同时拿到两个值，避免长时间轮询时频繁建连。
     */
    private long[] readAgentSaveState(Host host, HostAgentService.AgentContext context,
                                      String exitPath, String tarPath) {
        try {
            ContainerService.CommandResult result = containerService.exec(host, context.containerId(), List.of(
                    "sh", "-c",
                    "c=-1; if [ -f \"$1\" ]; then c=$(cat \"$1\" | tr -d '\\n'); fi;"
                            + " s=0; if [ -f \"$2\" ]; then s=$(stat -c %s \"$2\" 2>/dev/null || echo 0); fi;"
                            + " echo \"$s|$c\"",
                    "sh", exitPath, tarPath));
            String out = StrUtil.trimToEmpty(result.stdout());
            int bar = out.lastIndexOf('|');
            if (bar <= 0) {
                return new long[]{0L, -1L};
            }
            long size = parseLong(StrUtil.trimToEmpty(out.substring(0, bar)), 0L);
            long code = parseLong(StrUtil.trimToEmpty(out.substring(bar + 1)), -1L);
            return new long[]{size, code};
        } catch (Exception e) {
            throw new IllegalStateException("读取辅助容器内的打包进度失败（容器可能已停止）：" + e.getMessage(), e);
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String readAgentText(Host host, HostAgentService.AgentContext context, String path) {
        try {
            ContainerService.CommandResult result = containerService.exec(host, context.containerId(),
                    List.of("sh", "-c", "cat \"$1\" 2>/dev/null", "sh", path));
            return StrUtil.trimToEmpty(result.stdout());
        } catch (Exception e) {
            return "";
        }
    }

    private void removeAgentDir(Host host, HostAgentService.AgentContext context, String dir) {
        execQuietly(host, context, "rm -rf " + shellQuote(dir));
    }

    private void execOrThrow(Host host, HostAgentService.AgentContext context, String command) {
        ContainerService.CommandResult result = containerService.exec(host, context.containerId(),
                List.of("sh", "-c", command));
        if (result.exitCode() != 0) {
            String stderr = StrUtil.trimToEmpty(result.stderr());
            throw new IllegalStateException(stderr.isBlank()
                    ? "辅助容器内命令执行失败: " + command
                    : "辅助容器内命令执行失败：" + stderr);
        }
    }

    private void execQuietly(Host host, HostAgentService.AgentContext context, String command) {
        try {
            containerService.exec(host, context.containerId(), List.of("sh", "-c", command));
        } catch (Exception e) {
            log.debug("辅助容器内清理命令失败: {}", e.getMessage());
        }
    }

    /**
     * 辅助容器是否可用：未部署或连接失败时返回 null，调用方回落到原来的保存方式。
     */
    private HostAgentService.AgentContext agentContext(Host host) {
        try {
            HostAgentVo vo = hostAgentService.status(host);
            if (!vo.isDeployed()) {
                fileLog.info("该主机未部署辅助容器，tar 将按原来的方式保存");
                return null;
            }
            return hostAgentService.requireRunning(host);
        } catch (Exception e) {
            fileLog.warn("辅助容器不可用（{}），tar 将按原来的方式保存", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 任务登记 / 网页下载

    /**
     * 登记已完成的 tar，供镜像下载页显示下载按钮；超过容量时淘汰最早的登记（不删文件）。
     */
    private void registerSaved(String logId, String hostId, TarResult result) {
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
        savedTars.put(logId, new SavedTar(hostId, result.getFileName(), result.getPath(),
                result.getRelativePath(), result.getSize(), result.getMode(), now));
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
        view.setMode(saved.mode().name());
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
        ImageDownloadTaskView view = taskView(logId);
        view.setReady(true);
        return view;
    }

    /**
     * 把已生成的 tar 流式写给浏览器：辅助容器走 Docker archive 接口，
     * SSH 主机走 SFTP，平台侧文件直接读盘。不删除源文件。
     */
    public void copyTarTo(String logId, OutputStream out) throws Exception {
        Assert.hasText(logId, "logId 不能为空");
        SavedTar saved = savedTars.get(logId);
        Assert.notNull(saved, "任务不存在或已失效，请重新下载镜像");
        Host host = hostService.findById(saved.hostId()).orElse(null);
        Assert.notNull(host, "执行主机不存在");
        switch (saved.mode()) {
            case AGENT -> {
                HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
                String path = HostRootPolicy.toAgentPath(saved.relativePath());
                ContainerService.CommandResult exists = containerService.exec(host, context.containerId(),
                        List.of("sh", "-c", "test -f \"$1\"", "sh", path));
                if (exists.exitCode() != 0) {
                    throw new IllegalStateException("tar 文件已不存在（辅助容器可能已重建或挂载目录已切换），请重新下载镜像");
                }
                containerService.downloadFile(host, context.containerId(), path, out);
            }
            case SSH -> copyTarTo(saved.hostId(), saved.path(), true, out);
            case PLATFORM -> copyTarTo(saved.hostId(), saved.path(), false, out);
        }
    }

    /**
     * 删除已生成的 tar（连同登记），给用户腾磁盘的入口；删除失败时抛出让前端提示。
     */
    public void deleteTar(String logId) {
        Assert.hasText(logId, "logId 不能为空");
        SavedTar saved = savedTars.get(logId);
        Assert.notNull(saved, "任务不存在或已失效");
        Host host = hostService.findById(saved.hostId()).orElse(null);
        Assert.notNull(host, "执行主机不存在");
        switch (saved.mode()) {
            case AGENT -> {
                HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
                String relativeDir = StrUtil.subBefore(saved.relativePath(), "/", true);
                execOrThrow(host, context, "rm -rf " + shellQuote(HostRootPolicy.toAgentPath(relativeDir)));
            }
            case SSH -> {
                Session session = null;
                try {
                    session = openSshSession(host);
                    execQuietly(session, "rm -rf " + shellQuote(parentOf(saved.path())));
                } catch (Exception e) {
                    throw new IllegalStateException("删除主机上的 tar 失败：" + e.getMessage(), e);
                } finally {
                    if (session != null) {
                        session.disconnect();
                    }
                }
            }
            case PLATFORM -> FileUtil.del(parentOf(saved.path()));
        }
        savedTars.remove(logId);
    }

    /**
     * Linux 风格的父目录；找不到分隔符时返回原路径。
     */
    private static String parentOf(String path) {
        int idx = path == null ? -1 : path.lastIndexOf('/');
        return idx <= 0 ? StrUtil.nullToEmpty(path) : path.substring(0, idx);
    }


    // ------------------------------------------------------------------ 辅助

    /**
     * 平台侧 tar 目录：放在日志目录的上一级，容器部署时即挂载出来的 /data/image-tar。
     */
    private static File localTarDir(String logId) {
        String logPath = SpringTool.getProperty("logging.file.path");
        File base = StrUtil.isBlank(logPath)
                ? new File("/data")
                : new File(logPath).getAbsoluteFile().getParentFile();
        if (base == null) {
            base = new File(".");
        }
        return new File(new File(base, LOCAL_TAR_DIR_NAME), logId);
    }

    /**
     * 本机主机：连接方式为本机，或没有配置远程地址（未配置 dockerHost 时客户端走平台默认端点）。
     */
    private static boolean isLocalHost(Host host) {
        return host == null || host.isLocalDocker();
    }

    private Session openSshSession(Host host) throws Exception {
        // 与 Docker 传输共用主机 SSH 连接配置：地址、账号密码与连接代理都在这里生效
        return dockerClientManager.createSshSession(host, SSH_TIMEOUT_MILLIS, 60_000);
    }

    /**
     * 远端文件大小；文件尚未创建时返回 0。
     */
    private static long remoteSize(ChannelSftp sftp, String path) {
        try {
            SftpATTRS attrs = sftp.lstat(path);
            return attrs == null ? 0L : attrs.getSize();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 执行一条清理命令（如删除半截 tar），失败不影响主流程。
     */
    private static void execQuietly(Session session, String command) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.connect(SSH_TIMEOUT_MILLIS);
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 清理失败就留在主机上，不影响已产生的错误信息
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 与 DockerClientManager 的 SSH 传输一致：允许用附加文件补充远端 PATH。
     */
    private static String additionalFileToSourcePrefix() {
        String file = System.getenv("JSCH_DOCKER_ADDITIONAL_FILE_TO_SOURCE");
        return StrUtil.isBlank(file) ? "" : "source " + shellQuote(file) + " && ";
    }

    /**
     * 单引号包裹命令参数，防止镜像地址里的特殊字符被 shell 解释。
     * （镜像地址已过白名单，这里只是双保险。）
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
            List<String> tags = response.getRepoTags();
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
            return "ssh://" + StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER) + "@" + sshHost;
        }
        return StrUtil.blankToDefault(host.getDockerHost(), "本机");
    }

}
