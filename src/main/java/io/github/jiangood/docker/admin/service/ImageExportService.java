package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.jiangood.docker.admin.websocket.ImageExportLogHandshakeInterceptor;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * 镜像导出：从注册中心把镜像拉到本机 docker，再以 {@code docker save} 的 docker-archive 格式
 * 落到临时目录，供前端下载后 {@code docker load} 导入。
 * <p>
 * 整个过程是后台任务：慢的阶段（拉取、打包）不占用 HTTP 请求，进度写入
 * {@code /data/logs/{logId}.log}，前端通过 {@code /admin/ws/image-export-log/{logId}} 实时查看。
 * 打包完成后本机镜像会被清理；临时 tar 在下载完成后删除，超时未下载的由定时任务清扫。
 * <p>
 * 拉取复用 {@link AppService#getClient}：仅当镜像地址属于注册中心时才附带凭据，
 * 拉取公共镜像不会误带注册中心账号密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageExportService {

    /**
     * 临时 tar 目录（系统临时目录下，数据不重要、用完即弃）。
     */
    private static final String EXPORT_DIR_NAME = "docker-admin-exports";

    /**
     * 临时文件保留时长（分钟），配合下载后删除，兜底防止磁盘滞留。
     */
    private static final int TTL_MINUTES = 120;

    /** 打包阶段每写入多少字节上报一次进度。 */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    private final AppService appService;

    @Resource
    TaskLogRegistry taskLogRegistry;

    /**
     * 后台导出镜像。异常只写进导出日志（前端在日志里可见），不向上抛。
     */
    @Async
    public void export(String logId, String imageUrl, String tag) {
        taskLogRegistry.start(logId);
        MDC.put("logFileId", logId);

        String fullUrl = imageUrl + ":" + tag;
        DockerClient client = null;
        // 拉取前若探测失败，保守按「本来就有」处理，避免误删宿主机原有镜像
        boolean existedBefore = true;
        // 只有本次真的拉取成功，才需要导出后清理
        boolean pulled = false;
        File target = null;
        boolean success = false;
        try {
            File dir = exportDir();
            Assert.state(dir.isDirectory() || dir.mkdirs(), "导出目录不可用: " + dir.getAbsolutePath());
            sweepExpired(dir);

            target = new File(dir, logId + ".tar");
            Assert.state(!target.exists(), "导出文件已存在，请重试");

            log.info("开始导出镜像 {}", fullUrl);

            client = appService.getClient(null, imageUrl);
            existedBefore = exists(client, fullUrl);
            if (existedBefore) {
                log.info("本机已存在该镜像");
            }

            log.info("从注册中心拉取镜像…");
            pull(client, imageUrl, tag, fullUrl);
            pulled = true;
            log.info("拉取完成");

            log.info("生成归档（docker save，过程中仅上报字节数，无准确总量）…");
            long size = save(client, fullUrl, target);
            log.info("归档完成：{}（{}）", target.getName(), FileUtil.readableFileSize(size));

            success = true;
            log.info("导出完成，可以下载了");
        } catch (Exception e) {
            // 进度日志面向用户，只给一行可读信息；堆栈留给后端排查
            log.error("镜像导出失败: {}", e.getMessage());
            log.debug("镜像导出失败详情", e);
        } finally {
            if (!success && target != null) {
                FileUtil.del(target);
            }
            if (pulled && !existedBefore && client != null) {
                removeQuietly(client, fullUrl);
            }
            IOUtils.closeQuietly(client);
            MDC.remove("logFileId");
            taskLogRegistry.finish(logId);
        }
    }

    // ------------------------------------------------------------------ 下载支持

    /**
     * 导出文件是否已就绪（任务已结束且文件存在）。
     */
    public boolean isReady(String logId) {
        if (!isValidLogId(logId) || taskLogRegistry.isRunning(logId)) {
            return false;
        }
        File f = fileOf(logId);
        return f.isFile() && f.length() > 0;
    }

    public long sizeOf(String logId) {
        File f = fileOf(logId);
        return f.isFile() ? f.length() : 0L;
    }

    public File fileOf(String logId) {
        return new File(exportDir(), logId + ".tar");
    }

    public void delete(String logId) {
        FileUtil.del(fileOf(logId));
    }

    /**
     * logId 同时也是日志文件名与临时文件名，必须以此白名单约束，防止目录穿越。
     */
    public boolean isValidLogId(String logId) {
        return logId != null && ImageExportLogHandshakeInterceptor.LOG_ID_PATTERN.matcher(logId).matches();
    }

    // ------------------------------------------------------------------ 内部

    private void pull(DockerClient client, String imageUrl, String tag, String fullUrl) {
        try {
            // 必须 repo 与 tag 分开传：docker-java 不拆分 "repo:tag"，
            // 空 tag 会被 Docker 理解为「拉取该仓库全部 tag」。
            client.pullImageCmd(imageUrl)
                    .withTag(tag)
                    .exec(new DefaultCallback<PullResponseItem>(MDC.get("logFileId")))
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("拉取镜像被中断: " + fullUrl, e);
        }
    }

    private long save(DockerClient client, String fullUrl, File target) throws IOException {
        long written = 0;
        long lastReport = 0;
        try (InputStream in = client.saveImageCmd(fullUrl).exec();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (written - lastReport >= PROGRESS_STEP_BYTES) {
                    log.info("已写入 {}…", FileUtil.readableFileSize(written));
                    lastReport = written;
                }
            }
        }
        return written;
    }

    /**
     * 探测本机是否已存在该镜像。探测失败时保守返回 true（宁可不清、不可误删）。
     */
    private boolean exists(DockerClient client, String fullUrl) {
        try {
            client.inspectImageCmd(fullUrl).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            log.warn("探测本机镜像失败，按已存在处理 {}: {}", fullUrl, e.getMessage());
            return true;
        }
    }

    private void removeQuietly(DockerClient client, String fullUrl) {
        try {
            client.removeImageCmd(fullUrl).exec();
            log.info("已清理本机镜像 {}", fullUrl);
        } catch (Exception e) {
            log.warn("清理本机镜像失败 {}: {}", fullUrl, e.getMessage());
        }
    }

    private long inspectSize(DockerClient client, String fullUrl) {
        try {
            InspectImageResponse r = client.inspectImageCmd(fullUrl).exec();
            return r.getSize() == null ? 0L : r.getSize();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 清扫超过保留时长的导出文件（防止用户导出了却没下载导致磁盘滞留）。
     */
    private void sweepExpired(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tar"));
        if (files == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - TTL_MINUTES * 60_000L;
        for (File f : files) {
            if (f.lastModified() < deadline && f.delete()) {
                // 用 debug 级别，避免混进当前任务的导出日志
                log.debug("清理过期导出文件 {}", f.getName());
            }
        }
    }

    private static File exportDir() {
        return new File(System.getProperty("java.io.tmpdir"), EXPORT_DIR_NAME);
    }
}
