package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.SpringTool;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
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
import java.util.regex.Pattern;

/**
 * 主机镜像 tar：在指定主机上执行 {@code docker save}，把镜像保存成 tar 包。
 * <p>
 * 两种入口共用同一套保存逻辑：
 * <ul>
 *   <li>镜像下载页：先从注册中心 / 公共仓库拉取，再保存（{@code pull=true}）；</li>
 *   <li>主机详情页：直接导出主机上已有的镜像（{@code pull=false}）。</li>
 * </ul>
 * tar 包尽量留在执行主机上：
 * <ul>
 *   <li>SSH 主机：通过 SSH 执行远端 {@code docker save}，落到 {@code /tmp/docker-admin-image-tar/{logId}/}；</li>
 *   <li>本机主机：由平台的 docker 客户端读取 save 流，写到 {@code {数据目录}/image-tar/{logId}/}；</li>
 *   <li>TCP 远程主机：docker API 没有写主机文件的接口，tar 会落到平台服务器，日志里会明确提示。</li>
 * </ul>
 * 过程写入 {@code {日志目录}/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Service
@RequiredArgsConstructor
public class ImageDownloadService {

    private static final Logger fileLog = FileLog.logger();

    /**
     * SSH 主机上的 tar 存放目录。
     */
    private static final String REMOTE_TAR_DIR = "/tmp/docker-admin-image-tar";

    /**
     * 平台侧 tar 目录名，放在日志目录的上一级（容器部署即 /data/image-tar）。
     */
    private static final String LOCAL_TAR_DIR_NAME = "image-tar";

    /**
     * 镜像地址白名单：禁止空格与 shell 元字符，SSH 主机上会拼进 docker 命令。
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:\\-]*$");

    /**
     * 每写入多少字节上报一次进度日志。
     */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    private static final int SSH_TIMEOUT_MILLIS = 10_000;

    private static final int DEFAULT_SSH_PORT = 22;

    private static final String DEFAULT_SSH_USER = "root";

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerClientManager;

    /**
     * 在主机上生成 tar 的结果：下载弹窗展示路径、大小，并据此决定走本地读盘还是 SFTP。
     */
    public static class TarResult {

        private final String fileName;
        private final String path;
        private final long size;
        private final boolean remote;

        public TarResult(String fileName, String path, long size, boolean remote) {
            this.fileName = fileName;
            this.path = path;
            this.size = size;
            this.remote = remote;
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
     * 镜像下载页：拉取镜像后打成 tar。
     */
    @Async
    public void download(String logId, ImageDownloadRequest p) {
        taskLogRegistry.start(logId);
        FileLog.bind(logId);
        try {
            saveToHost(logId, p.getHostId(), p.getImage(), true, null);
            fileLog.info("镜像下载完成");
        } catch (Exception e) {
            // 面向用户只给一行原因，堆栈留给后端排查
            fileLog.error("镜像下载失败: {}", e.getMessage());
            fileLog.debug("镜像下载失败详情", e);
        } finally {
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 在主机上执行 {@code docker save} 并保存 tar。
     * <p>
     * 调用方需先 {@link FileLog#bind(String)} 绑定日志文件；本方法只负责保存，
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
                        .exec(new DefaultCallback<PullResponseItem>(logId))
                        .awaitCompletion();
                fileLog.info("拉取完成 {}:{}", repo, tag);
            }

            String fileName = resolveFileName(client, ref);

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
            return new TarResult(fileName, path, size, true);
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
        return new TarResult(fileName, target.getAbsolutePath(), written, false);
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

    private static Session openSshSession(Host host) throws Exception {
        String user = StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER);
        String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
        int port = host.getSshPort() != null ? host.getSshPort() : DEFAULT_SSH_PORT;

        JSch jsch = new JSch();
        Session session = jsch.getSession(user, sshHost, port);
        // 与 DockerClientManager 的 SSH 传输保持一致：内网不校验 host key，只走密码认证
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setPassword(StrUtil.nullToEmpty(host.getSshPassword()));
        // 远端打包期间一直有 SFTP 轮询，60 秒无响应即视为连接异常，避免任务永久挂住
        session.setTimeout(60_000);
        session.connect(SSH_TIMEOUT_MILLIS);
        return session;
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
