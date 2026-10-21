package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
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
import java.util.regex.Pattern;

/**
 * 镜像下载 tar：在指定主机上拉取镜像，再用 {@code docker save} 打成 tar 包。
 * <p>
 * tar 包保存在执行主机上：
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
     * 每写入多少字节上报一次进度。
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

    @Async
    public void download(String logId, ImageDownloadRequest p) {
        taskLogRegistry.start(logId);
        FileLog.bind(logId);
        DockerClient client = null;
        try {
            String image = StrUtil.trim(p.getImage());
            String repo = repoOf(image);
            String tag = tagOf(image);
            String ref = repo + ":" + tag;
            // 文件名取镜像名最后一段，避免把注册中心地址带进文件名
            String fileName = fileName(repo, tag);

            Host host = hostService.findById(StrUtil.trim(p.getHostId())).orElse(null);
            Assert.notNull(host, "执行主机不存在");

            fileLog.info("开始下载镜像 {}", ref);
            fileLog.info("执行主机: {} ({})", host.getName(), endpoint(host));

            client = dockerClientManager.getClient(host);
            fileLog.info("拉取镜像 {}", ref);
            client.pullImageCmd(repo)
                    .withTag(tag)
                    .exec(new DefaultCallback<PullResponseItem>(logId))
                    .awaitCompletion();
            fileLog.info("拉取完成 {}", ref);

            if (host.isSsh()) {
                saveOnSshHost(host, ref, logId, fileName);
            } else {
                saveOnPlatform(client, ref, logId, fileName, host);
            }
            fileLog.info("镜像下载完成");
        } catch (Exception e) {
            // 面向用户只给一行原因，堆栈留给后端排查
            fileLog.error("镜像下载失败: {}", e.getMessage());
            fileLog.debug("镜像下载失败详情", e);
        } finally {
            IOUtils.closeQuietly(client);
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    // ------------------------------------------------------------------ 保存 tar

    /**
     * SSH 主机：在远端执行 {@code docker save > 文件}，期间轮询文件大小上报进度。
     */
    private void saveOnSshHost(Host host, String ref, String logId, String fileName) throws Exception {
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
            channel.setCommand("mkdir -p " + shellQuote(dir) + " && " + additionalFileToSourcePrefix() + "docker save "
                    + shellQuote(ref) + " > " + shellQuote(path));
            channel.setInputStream(null);
            channel.setErrStream(err);
            channel.connect(SSH_TIMEOUT_MILLIS);

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(SSH_TIMEOUT_MILLIS);

            long lastSize = -1;
            long lastLogAt = System.currentTimeMillis();
            while (!channel.isClosed()) {
                Thread.sleep(500);
                long size = remoteSize(sftp, path);
                long now = System.currentTimeMillis();
                if (size != lastSize && now - lastLogAt >= 2000) {
                    fileLog.info("已写入 {}…", FileUtil.readableFileSize(size));
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
            fileLog.info("tar 包已保存：{}（{}）", path, FileUtil.readableFileSize(size));
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
    private void saveOnPlatform(DockerClient client, String ref, String logId, String fileName, Host host) throws Exception {
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
        try (InputStream in = client.saveImageCmd(ref).exec();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (written - lastReport >= PROGRESS_STEP_BYTES) {
                    fileLog.info("已写入 {}…", FileUtil.readableFileSize(written));
                    lastReport = written;
                }
            }
        } catch (Exception e) {
            // 失败时清掉半截文件，避免用户误用
            FileUtil.del(target);
            throw e;
        }

        fileLog.info("tar 包已保存：{}（{}）", target.getAbsolutePath(), FileUtil.readableFileSize(written));
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
     * tar 文件名：镜像名最后一段 + tag，如 mysql-8.tar。
     */
    private static String fileName(String repo, String tag) {
        String name = repo.substring(repo.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeTag = tag.replaceAll("[^A-Za-z0-9._-]", "_");
        return StrUtil.blankToDefault(name, "image") + "-" + StrUtil.blankToDefault(safeTag, "latest") + ".tar";
    }

    private static String endpoint(Host host) {
        if (host.isSsh()) {
            String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
            return "ssh://" + StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER) + "@" + sshHost;
        }
        return StrUtil.blankToDefault(host.getDockerHost(), "本机");
    }

}
