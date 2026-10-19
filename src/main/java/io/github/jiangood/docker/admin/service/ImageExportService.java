package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.jiangood.docker.admin.dto.ImageExportTaskView;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.websocket.ImageExportLogHandshakeInterceptor;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像导出：从注册中心把镜像拉到本机 docker，再以 {@code docker save} 的 docker-archive 格式
 * 落到临时目录，供前端下载后 {@code docker load} 导入。
 * <p>
 * 整个过程是后台任务：慢的阶段（拉取、打包）不占用 HTTP 请求，进度写入
 * {@code /data/logs/{logId}.log}，前端通过 {@code /admin/ws/image-export-log/{logId}} 实时查看。
 * 临时 tar 在下载完成后删除，超时未下载的在后续导出时被清扫。
 * <p>
 * 导出拉进来的本机镜像<b>保留不删</b>：同一个 tag 再导出可直接复用，也给应用部署留了现成的镜像。
 * 磁盘占用交回既有策略——{@code CleanImageJob} 会 prune 长期未使用的镜像（前提是本机已登记为主机）。
 * <p>
 * 拉取复用 {@link AppService#getClient}：仅当镜像地址属于注册中心时才附带凭据，
 * 拉取公共镜像不会误带注册中心账号密码。
 * <p>
 * 若本机已有该镜像且摘要与登记一致（见 {@link #knownDigest}），跳过拉取直接打包：
 * 内容相同，结果与重新拉取完全一样，省掉一次注册中心往返，也让离线主机能导出已有镜像。
 * <p>
 * 任务由调用方（控制器）在 {@link ImageExportRegistry} 登记后启动，本类只在结束时更新状态，
 * 以便列表页展示「导出中 / 可下载 / 失败」。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageExportService {

    private static final Logger fileLog = FileLog.logger();

    /**
     * 临时 tar 目录（系统临时目录下，数据不重要、用完即弃）。
     */
    private static final String EXPORT_DIR_NAME = "docker-admin-exports";

    /**
     * 临时文件保留时长（分钟），配合下载后删除，兜底防止磁盘滞留。
     */
    private static final int TTL_MINUTES = 120;

    /**
     * 导出目录容量上限：已占用这么多就拒绝新的导出，提示用户手动清理，
     * 避免悄悄把磁盘写满（tar 是下载即删的临时文件，本不该无上限堆积）。
     */
    private static final long MAX_DIR_BYTES = 50L * 1024 * 1024 * 1024;

    /** 打包阶段每写入多少字节上报一次进度。 */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    private final AppService appService;

    private final ImageExportRegistry exportRegistry;

    private final ImageTagService imageTagService;

    @Resource
    TaskLogRegistry taskLogRegistry;

    /**
     * 后台导出镜像。异常只写进导出日志（前端在日志里可见），不向上抛。
     * <p>
     * 任务已由 {@link ImageExportRegistry} 登记，这里只负责结束时更新状态。
     */
    @Async
    public void export(String logId, String imageUrl, String tag) {
        taskLogRegistry.start(logId);
        FileLog.bind(logId);

        String fullUrl = imageUrl + ":" + tag;
        DockerClient client = null;
        File target = null;
        boolean success = false;
        try {
            File dir = exportDir();
            Assert.state(dir.isDirectory() || dir.mkdirs(), "导出目录不可用: " + dir.getAbsolutePath());
            // 控制器派发前已在 assertDirCapacity 里回收过一次，这里是任务侧兜底，幂等
            sweepExpired(dir);

            target = new File(dir, logId + ".tar");
            Assert.state(!target.exists(), "导出文件已存在，请重试");

            fileLog.info("开始导出镜像 {}", fullUrl);

            client = appService.getClient(null, imageUrl);

            // 探测本机镜像，顺带取回它的摘要（配置摘要，等于镜像 ID），用于判断能否跳过拉取。
            // 不存在与探测失败都归为「摘要未知」，本次老实拉取，绝不做没有把握的跳过。
            String localDigest = null;
            try {
                localDigest = client.inspectImageCmd(fullUrl).exec().getId();
            } catch (Exception e) {
                fileLog.debug("探测本机镜像 {} 失败：{}", fullUrl, e.getMessage());
            }

            String knownDigest = knownDigest(imageUrl, tag);
            if (StrUtil.isNotBlank(localDigest) && StrUtil.isNotBlank(knownDigest) && knownDigest.equals(localDigest)) {
                // 摘要一致即内容一致（配置摘要覆盖了 rootfs 各层的 diff_id），
                // docker save 的结果与重新拉取完全相同，省掉一次注册中心往返
                fileLog.info("本机已存在该镜像且摘要一致，跳过拉取");
            } else {
                if (StrUtil.isNotBlank(localDigest)) {
                    fileLog.info("本机已存在该镜像，但摘要与登记不一致，仍从注册中心拉取");
                }
                fileLog.info("从注册中心拉取镜像…");
                pull(client, imageUrl, tag, fullUrl);
                fileLog.info("拉取完成");
            }

            fileLog.info("生成归档（docker save，过程中仅上报字节数，无准确总量）…");
            long size = save(client, fullUrl, target);
            fileLog.info("归档完成：{}（{}）", target.getName(), FileUtil.readableFileSize(size));

            success = true;
            fileLog.info("导出完成，可以下载了");
        } catch (Exception e) {
            // 进度日志面向用户，只给一行可读信息；堆栈留给后端排查
            fileLog.error("镜像导出失败: {}", e.getMessage());
            fileLog.debug("镜像导出失败详情", e);
        } finally {
            if (!success && target != null) {
                FileUtil.del(target);
            }
            IOUtils.closeQuietly(client);
            FileLog.unbind();
            taskLogRegistry.finish(logId);
            exportRegistry.finish(logId, success
                    ? ImageExportRegistry.Status.SUCCESS
                    : ImageExportRegistry.Status.FAILED);
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
     * 下载成功或手动删除后清理：删临时文件，同时移除登记表条目，前端轮询会随之收起下载状态。
     */
    public void deleteAndUnregister(String logId) {
        // logId 同时是文件名，必须走白名单校验，防止目录穿越
        Assert.state(isValidLogId(logId), "非法的导出任务标识");
        delete(logId);
        ImageExportRegistry.Task task = exportRegistry.getByLogId(logId);
        if (task != null) {
            exportRegistry.remove(task.getImageUrl(), task.getTag());
        }
    }

    // ------------------------------------------------------------------ 容量

    /**
     * 导出目录容量校验：先回收过期的，再看是否已超上限，超了直接拒绝本次导出。
     * <p>
     * 由控制器在派发任务前（请求线程）调用，让用户点「确定」时就拿到失败原因，
     * 而不是等后台任务跑起来才在日志里报错。超限时只提示、不自动删——
     * 删哪个由用户决定（导出抽屉右上角的「删除」）。
     */
    public void assertDirCapacity() {
        File dir = exportDir();
        // 先腾掉过期文件，否则会出现「本来能清出来却仍被拒」的憋屈情况
        sweepExpired(dir);
        long used = tarBytes(dir);
        Assert.state(used < MAX_DIR_BYTES,
                "导出目录已占用 " + FileUtil.readableFileSize(used)
                        + "，超过上限 " + FileUtil.readableFileSize(MAX_DIR_BYTES)
                        + "，请手动删除已导出的 tar 后再试");
    }

    /**
     * 导出目录里所有 tar 的总字节数。
     */
    private static long tarBytes(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tar"));
        if (files == null) {
            return 0L;
        }
        long sum = 0L;
        for (File f : files) {
            sum += f.length();
        }
        return sum;
    }

    // ------------------------------------------------------------------ 状态查询

    /**
     * 前端展示用的导出状态：登记信息 + 归档是否就绪、多大。
     */
    public ImageExportTaskView taskView(ImageExportRegistry.Task task) {
        if (task == null) {
            return null;
        }
        ImageExportTaskView view = new ImageExportTaskView();
        view.setImageUrl(task.getImageUrl());
        view.setTag(task.getTag());
        view.setLogId(task.getLogId());
        view.setStatus(task.getStatus().name());
        view.setStartTime(task.getStartTime());
        boolean ready = isReady(task.getLogId());
        view.setReady(ready);
        view.setSize(ready ? sizeOf(task.getLogId()) : 0L);
        return view;
    }

    public ImageExportTaskView taskView(String imageUrl, String tag) {
        return taskView(exportRegistry.get(imageUrl, tag));
    }

    public ImageExportTaskView taskViewByLogId(String logId) {
        return taskView(exportRegistry.getByLogId(logId));
    }

    /**
     * 某镜像下所有 tag 的导出状态，列表页一次拿全。
     */
    public List<ImageExportTaskView> taskViews(String imageUrl) {
        List<ImageExportTaskView> list = new ArrayList<>();
        for (ImageExportRegistry.Task task : exportRegistry.list(imageUrl)) {
            list.add(taskView(task));
        }
        return list;
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
                    .exec(new DefaultCallback<PullResponseItem>(FileLog.currentLogId()))
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
     * 该 tag 已登记的镜像摘要（配置摘要，等于镜像 ID）：远程同步与本机构建后都会写入。
     * <p>
     * 拿它和本机镜像摘要比对即可判断「本机这份是不是注册中心那一份」，从而跳过拉取。
     * <b>但这是上次同步时记下的值</b>：若镜像在别处重新推送过同名 tag，这里会过期，
     * 按旧摘要跳过就可能导出旧内容。所以只在值存在时才跳过（缺失一律老实拉取），
     * 且这份取舍适合 tag 不可变的场景（v1.2.3）；若担心 latest 之类的可变 tag 被别处覆盖，
     * 就别用这里，改为向注册中心发一次 manifest 查询拿权威摘要（成本与 docker pull 的
     * 版本比对几乎相同，省不下什么）。
     */
    private String knownDigest(String imageUrl, String tag) {
        return imageTagService.find(imageUrl, tag).map(ImageTag::getDigest).orElse(null);
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
        // 登记表同步瘦身：文件已不在的条目没有保留价值
        exportRegistry.sweep(TTL_MINUTES * 60_000L, logId -> !new File(dir, logId + ".tar").exists());
    }

    private static File exportDir() {
        return new File(System.getProperty("java.io.tmpdir"), EXPORT_DIR_NAME);
    }
}
