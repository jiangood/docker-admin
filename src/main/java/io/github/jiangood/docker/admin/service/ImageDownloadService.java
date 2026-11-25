package io.github.jiangood.docker.admin.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.jiangood.docker.admin.dao.ImageDownloadRecordRepository;
import io.github.jiangood.docker.admin.dto.ImageDownloadCleanResult;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadTaskView;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.ImageDownloadRecord;
import io.github.jiangood.docker.sdk.engine.DefaultCallback;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.modules.logviewer.util.TaskLog;
import io.github.jiangood.openadmin.modules.task.entity.SysTaskLog;
import io.github.jiangood.openadmin.modules.task.registry.TaskLogRegistry;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 镜像 tar：读取 docker 的 {@code save} 流，统一保存到平台服务器，网页从平台流式下载。
 * <p>
 * 每条任务在 {@code t_image_download_record} 落一条记录，tar 按 logId 分目录保存。
 * 新任务不会清理其他用户的文件；归档保留到用户在表格中勾选批量清理，下载完成后也不自动删除。
 * 过程写入 {@code {日志目录}/{logId}.log}，前端通过 WebSocket 实时查看。
 */
@Service
@Slf4j
public class ImageDownloadService {

    private static final Logger fileLog = TaskLog.logger();

    /** 平台侧 tar 目录名，放在日志目录的上一级（容器部署即 /data/image-tar）。 */
    private static final String LOCAL_TAR_DIR_NAME = "image-tar";

    /** 镜像地址白名单：禁止空格与 shell 元字符。 */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:\\-]*$");

    /** 每写入多少字节上报一次进度日志。 */
    private static final long PROGRESS_STEP_BYTES = 10L * 1024 * 1024;

    /** 单次批量清理上限，避免一次请求长时间占用数据库锁。 */
    private static final int MAX_CLEAN_BATCH = 200;

    private static final int MAX_ERROR_LENGTH = 1000;

    @Resource
    TaskLogRegistry taskLogRegistry;

    @Resource
    SysTaskLogService taskLogService;

    @Resource
    HostService hostService;

    @Resource
    DockerClientManager dockerClientManager;

    @Resource
    ImageDownloadRecordRepository recordRepository;

    @Resource
    PlatformTransactionManager transactionManager;

    @Value("${logging.file.path:}")
    private String logPath;

    /**
     * 正在流式下载的归档：多人同时下载同一文件时，全部结束前不允许清理。
     */
    private final Map<String, AtomicInteger> activeDownloads = new ConcurrentHashMap<>();

    /** 一次已完成保存的 tar，logId 同时是目录名。 */
    public record TarResult(String fileName, String path, long size) {
    }

    /** 镜像 tar 导出进度：已写入字节数与当前写入速度（字节/秒）。 */
    @FunctionalInterface
    public interface TarProgress {
        void update(long written, long bytesPerSecond);
    }

    /** 写入速度采样：按固定间隔取增量，再做指数平滑，避免速度数字剧烈跳动。 */
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
    public Host validate(ImageDownloadRequest p) {
        Assert.notNull(p, "请求不能为空");
        String image = StrUtil.trim(p.getImage());
        Assert.hasText(image, "请输入镜像地址");
        Assert.isTrue(IMAGE_PATTERN.matcher(image).matches(), "镜像地址格式不正确");
        String hostId = StrUtil.trim(p.getHostId());
        Assert.hasText(hostId, "请选择执行主机");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "执行主机不存在");
        return host;
    }

    /**
     * 先落业务记录与任务记录，再启动异步任务，保证前端拿到 logId 后日志 WS 能立即鉴权。
     */
    @Transactional
    public String start(ImageDownloadRequest request) {
        Host host = validate(request);
        String image = StrUtil.trim(request.getImage());
        String triggerUser = taskLogService.currentTriggerUser();
        String logId = "image-download-" + RandomUtil.randomString(16);

        ImageDownloadRecord record = new ImageDownloadRecord();
        record.setLogId(logId);
        record.setImage(image);
        record.setHostId(host.getId());
        record.setHostName(host.getName());
        record.setTriggerUser(triggerUser);
        record.setStatus(ImageDownloadRecord.RUNNING);
        record.setReady(false);
        recordRepository.saveAndFlush(record);

        taskLogService.start(logId, SysTaskLog.TYPE_IMAGE_DOWNLOAD,
                "下载镜像 " + StrUtil.sub(image, 0, 120),
                "image-download:download", triggerUser);
        // 必须在请求线程先登记，避免异步线程尚未启动时 WS 误判任务已结束。
        taskLogRegistry.start(logId);
        return logId;
    }

    /**
     * 镜像下载页：拉取镜像后打成 tar，并更新数据库记录供网页下载。
     */
    @Async
    public void download(String logId, ImageDownloadRequest p) {
        TaskLog.bind("task/" + logId);
        boolean success = false;
        String error = null;
        TarResult result = null;
        try {
            result = saveToHost(logId, p.getHostId(), p.getImage(), true, null);
            complete(logId, result);
            fileLog.info("镜像下载完成");
            success = true;
        } catch (Exception e) {
            // 文件已生成但业务记录收尾失败时，立即删掉完整归档，避免留下不可下载的孤儿文件。
            if (result != null) {
                deleteArchiveDirQuietly(logId);
            }
            // 面向用户只给一行原因，堆栈留给后端排查
            error = shortError(e);
            fail(logId, error);
            fileLog.error("镜像下载失败: {}", error);
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
     * 不负责业务记录、{@link TaskLogRegistry} 的任务登记与结束，也不会清理其他任务的文件。
     *
     * @param pull     是否先从仓库拉取镜像；主机详情页导出已有镜像时为 false
     * @param progress 进度回调（已写入字节数，当前速度），可为 null
     */
    public TarResult saveToHost(String logId, String hostId, String image, boolean pull,
                                TarProgress progress) throws Exception {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
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
     * 分页查询下载记录，并补齐文件是否仍可下载（不修改数据库）。
     */
    public Page<ImageDownloadRecord> page(String searchText, String status, Pageable pageable) {
        Spec<ImageDownloadRecord> q = Spec.of();
        q.orLike(searchText, ImageDownloadRecord.Fields.image, ImageDownloadRecord.Fields.hostName,
                ImageDownloadRecord.Fields.fileName, ImageDownloadRecord.Fields.logId);
        if (StrUtil.isNotBlank(status)) {
            q.eq(ImageDownloadRecord.Fields.status, StrUtil.trim(status));
        }
        Page<ImageDownloadRecord> page = recordRepository.findAll(q, pageable);
        page.getContent().forEach(this::fillReady);
        return page;
    }

    /**
     * 把 tar 流式写给调用方：调用方需先通过 {@link #reserveDownload(String)} 占用下载名额，
     * 平台上的文件直接读盘，不删除源文件。
     */
    public void copyTarTo(String logId, OutputStream out) throws Exception {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
        ImageDownloadRecord record = requireRecord(logId);
        Assert.state(ImageDownloadRecord.SUCCESS.equals(record.getStatus()), "任务尚未完成或归档已失效");
        File file = readyFile(record);
        copyFile(file.getAbsolutePath(), out);
    }

    /**
     * 把平台上的 tar 文件写给调用方（主机详情页的导出也复用这里）。
     */
    public void copyFile(String path, OutputStream out) throws Exception {
        File file = new File(path);
        Path root = tarRoot().toPath().toAbsolutePath().normalize();
        Path target = file.toPath().toAbsolutePath().normalize();
        Assert.state(target.startsWith(root), "tar 路径不合法");
        Assert.state(!Files.isSymbolicLink(target), "tar 文件不能是符号链接");
        Path realRoot = root.toRealPath();
        Path realTarget = target.toRealPath();
        Assert.state(realTarget.startsWith(realRoot), "tar 路径不合法");
        Assert.state(Files.isRegularFile(realTarget, LinkOption.NOFOLLOW_LINKS), "tar 文件已不存在: " + path);
        try (InputStream in = Files.newInputStream(realTarget, LinkOption.NOFOLLOW_LINKS)) {
            in.transferTo(out);
            out.flush();
        }
    }

    private TarResult saveOnPlatform(DockerClient client, String ref, String logId, String fileName,
                                     Host host, TarProgress progress) throws Exception {
        File dir = localTarDir(logId);
        Path dirPath = dir.toPath().toAbsolutePath().normalize();
        Assert.state(!Files.isSymbolicLink(dirPath), "tar 目录不能是符号链接");
        boolean dirCreated = !Files.exists(dirPath, LinkOption.NOFOLLOW_LINKS);
        if (dirCreated) {
            Files.createDirectories(dirPath);
        } else {
            Assert.state(Files.isDirectory(dirPath, LinkOption.NOFOLLOW_LINKS), "tar 目录不可用: " + dir.getAbsolutePath());
        }
        File target = new File(dir, fileName);

        if (!host.isLocalDocker()) {
            fileLog.info("远程主机的 docker API 无法写主机文件，tar 将保存到平台服务器，下载时从平台读取");
        }
        fileLog.info("生成 tar 包（docker save）…");

        long written = 0;
        long lastReport = 0;
        boolean targetCreated = false;
        SpeedMeter meter = new SpeedMeter();
        reportProgress(progress, 0L, 0L);
        try (InputStream in = client.saveImageCmd(ref).exec();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath(),
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            targetCreated = true;
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
            // 失败时只清掉本次创建的文件与目录；CREATE_NEW 冲突时不能误删已有归档。
            if (targetCreated) {
                deletePathQuietly(target.toPath());
            }
            if (dirCreated) {
                String[] remaining = dir.list();
                if (remaining == null || remaining.length == 0) {
                    deletePathQuietly(dirPath);
                }
            }
            throw e;
        }

        long speed = meter.update(written);
        reportProgress(progress, written, speed);
        fileLog.info("tar 包已保存：{}（{}）", target.getAbsolutePath(), FileUtil.readableFileSize(written));
        return new TarResult(fileName, target.getAbsolutePath(), written);
    }

    // ------------------------------------------------------------------ 记录状态 / 网页下载

    /** 任务成功收尾：先写业务记录，再由调用方结束通用任务记录。 */
    void complete(String logId, TarResult result) {
        ImageDownloadRecord record = requireRecord(logId);
        record.setStatus(ImageDownloadRecord.SUCCESS);
        record.setFileName(result.fileName());
        record.setPath(result.path());
        record.setSize(result.size());
        record.setEndTime(LocalDateTime.now());
        record.setError(null);
        record.setReady(true);
        recordRepository.saveAndFlush(record);
    }

    /** 任务失败收尾：保留记录与错误原因，供表格和任务中心追溯。 */
    void fail(String logId, String error) {
        recordRepository.findByLogId(logId).ifPresent(record -> {
            record.setStatus(ImageDownloadRecord.FAILED);
            record.setError(StrUtil.sub(error, 0, MAX_ERROR_LENGTH));
            record.setEndTime(LocalDateTime.now());
            record.setReady(false);
            recordRepository.saveAndFlush(record);
        });
    }

    /** 镜像下载页展示用：任务执行中或失败时 ready=false，进度与原因看实时日志。 */
    public ImageDownloadTaskView taskView(String logId) {
        if (!isValidLogId(logId)) {
            return null;
        }
        ImageDownloadRecord record = recordRepository.findByLogId(logId).orElse(null);
        if (record == null) {
            return null;
        }
        ImageDownloadTaskView view = new ImageDownloadTaskView();
        view.setLogId(record.getLogId());
        view.setImage(record.getImage());
        view.setHostName(record.getHostName());
        view.setFileName(record.getFileName());
        view.setPath(record.getPath());
        view.setSize(record.getSize() == null ? 0L : record.getSize());
        view.setStatus(record.getStatus());
        view.setError(record.getError());
        view.setReady(isReady(record));
        return view;
    }

    /** 下载前确认任务存在，并返回文件名 / 大小等响应头所需信息。 */
    public ImageDownloadTaskView readyTask(String logId) {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
        ImageDownloadRecord record = requireRecord(logId);
        Assert.state(ImageDownloadRecord.SUCCESS.equals(record.getStatus()), "任务尚未完成或归档已失效");
        Assert.state(isReady(record), "tar 文件已不存在，请重新下载镜像");
        return taskView(logId);
    }

    /**
     * 在写响应头前占用下载名额，关闭“检查文件 → 开始流式复制”之间的清理竞态。
     * 调用方无论成功失败都必须在 finally 中调用 {@link #releaseDownload(String)}。
     */
    public ImageDownloadTaskView reserveDownload(String logId) {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
        synchronized (activeDownloads) {
            activeDownloads.computeIfAbsent(logId, key -> new AtomicInteger()).incrementAndGet();
        }
        try {
            return readyTask(logId);
        } catch (RuntimeException e) {
            releaseDownload(logId);
            throw e;
        }
    }

    /** 释放一个下载名额；同一归档的最后一个下载结束时才允许清理。 */
    public void releaseDownload(String logId) {
        synchronized (activeDownloads) {
            AtomicInteger downloading = activeDownloads.get(logId);
            if (downloading != null && downloading.decrementAndGet() == 0) {
                activeDownloads.remove(logId, downloading);
            }
        }
    }

    /**
     * 批量清理选中的归档：运行中、正在下载、已清理或删除失败的记录会跳过。
     * 每条记录独立经历「CLEANING → 删文件 → CLEANED」，崩溃后可由启动清扫继续收尾。
     */
    public ImageDownloadCleanResult clean(List<String> logIds) {
        Assert.notEmpty(logIds, "请选择要清理的记录");
        Assert.isTrue(logIds.size() <= MAX_CLEAN_BATCH, "单次最多清理 200 条记录");
        ImageDownloadCleanResult result = new ImageDownloadCleanResult();
        String cleanedBy = taskLogService.currentTriggerUser();
        LocalDateTime now = LocalDateTime.now();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        // 固定加锁顺序，避免不同选择顺序的并发批次互相死锁。
        Set<String> uniqueLogIds = new TreeSet<>();
        for (String logId : logIds) {
            if (logId != null) {
                uniqueLogIds.add(logId);
            }
        }
        for (String logId : uniqueLogIds) {
            if (!isValidLogId(logId)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            Optional<String> previousStatus;
            synchronized (activeDownloads) {
                // 一直持有下载门闩到 CLEANING 提交，避免提交前 MVCC 仍读到 SUCCESS 而占位。
                previousStatus = transactions.execute(status -> beginClean(logId, cleanedBy, now));
            }
            if (previousStatus == null || previousStatus.isEmpty()) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            try {
                deleteTreeNoFollow(localTarDir(logId).toPath());
            } catch (Exception e) {
                log.warn("清理镜像归档失败: logId={} {}", logId, e.getMessage());
                transactions.executeWithoutResult(status -> restoreClean(logId, previousStatus.get()));
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            try {
                transactions.executeWithoutResult(status -> finishClean(logId));
                result.setCleaned(result.getCleaned() + 1);
            } catch (Exception e) {
                // 文件已删除，保留 CLEANING 状态，下次启动会补齐终态。
                log.warn("写入镜像清理结果失败: logId={} {}", logId, e.getMessage());
                result.setSkipped(result.getSkipped() + 1);
            }
        }
        return result;
    }

    /** 短事务：抢占一条记录进入 CLEANING，返回抢占前的状态。 */
    private Optional<String> beginClean(String logId, String cleanedBy, LocalDateTime now) {
        ImageDownloadRecord record = recordRepository.findByLogIdForUpdate(logId).orElse(null);
        if (record == null || ImageDownloadRecord.RUNNING.equals(record.getStatus())
                || ImageDownloadRecord.CLEANING.equals(record.getStatus())
                || ImageDownloadRecord.CLEANED.equals(record.getStatus())) {
            return Optional.empty();
        }
        synchronized (activeDownloads) {
            // 与下载占位共用锁：要么先占位并跳过清理，要么先进入 CLEANING、后续下载明确失败。
            if (activeDownloads.containsKey(logId)) {
                return Optional.empty();
            }
        }
        String previousStatus = record.getStatus();
        record.setStatus(ImageDownloadRecord.CLEANING);
        record.setReady(false);
        record.setCleanedTime(now);
        record.setCleanedBy(cleanedBy);
        if (record.getEndTime() == null) {
            record.setEndTime(now);
        }
        recordRepository.saveAndFlush(record);
        return Optional.of(previousStatus);
    }

    /** 删文件失败时恢复原状态，用户稍后可以重试。 */
    private void restoreClean(String logId, String previousStatus) {
        recordRepository.findByLogIdForUpdate(logId).ifPresent(record -> {
            if (!ImageDownloadRecord.CLEANING.equals(record.getStatus())) {
                return;
            }
            record.setStatus(previousStatus);
            record.setCleanedTime(null);
            record.setCleanedBy(null);
            fillReady(record);
            recordRepository.saveAndFlush(record);
        });
    }

    /** 文件已删除，补齐已清理终态。 */
    private void finishClean(String logId) {
        ImageDownloadRecord record = recordRepository.findByLogIdForUpdate(logId).orElse(null);
        if (record == null || !ImageDownloadRecord.CLEANING.equals(record.getStatus())) {
            return;
        }
        record.setStatus(ImageDownloadRecord.CLEANED);
        record.setReady(false);
        recordRepository.saveAndFlush(record);
    }

    /**
     * 平台重启清扫：运行中的任务标记失败并删除半截文件；清理中断的记录继续收尾；
     * 成功但文件已缺失的记录标记为已清理，失败任务的残留文件统一清掉。
     */
    @Transactional
    public int recoverAfterRestart() {
        LocalDateTime now = LocalDateTime.now();
        List<ImageDownloadRecord> running = recordRepository.findByStatus(ImageDownloadRecord.RUNNING);
        for (ImageDownloadRecord record : running) {
            record.setStatus(ImageDownloadRecord.FAILED);
            record.setError("平台重启，任务中断");
            record.setEndTime(now);
            record.setReady(false);
            recordRepository.saveAndFlush(record);
            deleteArchiveDirQuietly(record.getLogId());
        }

        List<ImageDownloadRecord> cleaning = recordRepository.findByStatus(ImageDownloadRecord.CLEANING);
        for (ImageDownloadRecord record : cleaning) {
            try {
                deleteTreeNoFollow(localTarDir(record.getLogId()).toPath());
                record.setStatus(ImageDownloadRecord.CLEANED);
                record.setReady(false);
                if (record.getCleanedTime() == null) {
                    record.setCleanedTime(now);
                }
                if (StrUtil.isBlank(record.getCleanedBy())) {
                    record.setCleanedBy("SYSTEM");
                }
                recordRepository.saveAndFlush(record);
            } catch (Exception e) {
                log.warn("继续清理镜像归档失败: logId={} {}", record.getLogId(), e.getMessage());
            }
        }

        List<ImageDownloadRecord> failed = recordRepository.findByStatus(ImageDownloadRecord.FAILED);
        for (ImageDownloadRecord record : failed) {
            deleteArchiveDirQuietly(record.getLogId());
        }

        List<ImageDownloadRecord> succeeded = recordRepository.findByStatus(ImageDownloadRecord.SUCCESS);
        for (ImageDownloadRecord record : succeeded) {
            if (!isReady(record)) {
                record.setStatus(ImageDownloadRecord.CLEANED);
                record.setCleanedTime(now);
                record.setCleanedBy("SYSTEM");
                record.setReady(false);
                recordRepository.saveAndFlush(record);
            }
        }
        importLegacyArchives();
        return running.size();
    }

    /**
     * 升级前只有内存登记，平台重启后磁盘上可能留下无法从页面访问的归档。
     * 把这类 image-download 目录补成历史记录：确认成功的可继续下载，其余保留为失败记录供用户清理。
     */
    private int importLegacyArchives() {
        File[] dirs = tarRoot().listFiles(file -> !Files.isSymbolicLink(file.toPath())
                && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && file.getName().startsWith("image-download-")
                && isValidLogId(file.getName()));
        if (dirs == null) {
            return 0;
        }
        int imported = 0;
        for (File dir : dirs) {
            String logId = dir.getName();
            if (recordRepository.findByLogId(logId).isPresent()) {
                continue;
            }
            File[] archives = dir.listFiles(file -> !Files.isSymbolicLink(file.toPath())
                    && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".tar"));
            if (archives == null || archives.length == 0) {
                deletePathQuietly(dir.toPath());
                continue;
            }
            Arrays.sort(archives, Comparator.comparing(File::getName));
            File archive = archives[0];
            SysTaskLog task = taskLogService.findByLogId(logId);
            boolean confirmed = task != null && SysTaskLog.STATUS_SUCCESS.equals(task.getStatus());
            String image = StrUtil.removeSuffix(archive.getName(), ".tar");
            if (task != null && StrUtil.startWith(task.getTitle(), "下载镜像 ")) {
                image = StrUtil.subAfter(task.getTitle(), "下载镜像 ", false);
            }

            ImageDownloadRecord record = new ImageDownloadRecord();
            record.setLogId(logId);
            record.setImage(image);
            record.setHostName("历史任务");
            record.setTriggerUser(task == null ? "历史记录" : task.getTriggerUser());
            // 只有任务中心确认成功的旧归档才提供下载；其余保留为失败记录，供用户清理。
            record.setStatus(confirmed ? ImageDownloadRecord.SUCCESS : ImageDownloadRecord.FAILED);
            record.setFileName(archive.getName());
            record.setPath(archive.getAbsolutePath());
            record.setSize(archive.length());
            record.setEndTime(task != null && task.getEndTime() != null
                    ? task.getEndTime()
                    : LocalDateTime.ofInstant(Instant.ofEpochMilli(archive.lastModified()), ZoneId.systemDefault()));
            record.setError(confirmed ? null : "历史任务未确认成功，已停止提供下载");
            record.setReady(confirmed);
            recordRepository.saveAndFlush(record);
            imported++;
        }
        if (imported > 0) {
            log.info("导入升级前遗留的镜像下载归档 {} 个", imported);
        }
        return imported;
    }

    /**
     * 主机详情页导出仍保留“新导出前清理旧导出”的策略，但只扫自己的前缀，
     * 不会影响镜像下载页已持久化的归档。
     */
    void cleanPreviousTars(String prefix, Set<String> runningLogIds) {
        try {
            File[] dirs = tarRoot().listFiles(file -> !Files.isSymbolicLink(file.toPath())
                    && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS));
            int removed = 0;
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir.getName().startsWith(prefix) && !runningLogIds.contains(dir.getName())) {
                        deleteTreeNoFollow(dir.toPath());
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                fileLog.info("已清理平台上旧的镜像导出 tar：{} 个", removed);
            }
        } catch (Exception e) {
            log.warn("清理旧的镜像导出 tar 失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 平台侧 tar 根目录：放在日志目录的上一级，容器部署时即挂载出来的 /data/image-tar。 */
    File tarRoot() {
        File base = StrUtil.isBlank(logPath)
                ? new File("/data")
                : new File(logPath).getAbsoluteFile().getParentFile();
        if (base == null) {
            base = new File(".");
        }
        return new File(base, LOCAL_TAR_DIR_NAME);
    }

    /** 单次任务的 tar 目录，目录名就是 logId。 */
    File localTarDir(String logId) {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
        return new File(tarRoot(), logId);
    }

    static boolean isValidLogId(String logId) {
        return logId != null && logId.matches("^[A-Za-z0-9_-]{1,64}$");
    }

    private ImageDownloadRecord requireRecord(String logId) {
        ImageDownloadRecord record = recordRepository.findByLogId(logId).orElse(null);
        Assert.notNull(record, "任务不存在或已失效，请重新下载镜像");
        return record;
    }

    private void fillReady(ImageDownloadRecord record) {
        record.setReady(isReady(record));
    }

    private boolean isReady(ImageDownloadRecord record) {
        if (record == null || !ImageDownloadRecord.SUCCESS.equals(record.getStatus())
                || StrUtil.isBlank(record.getPath())) {
            return false;
        }
        try {
            File file = readyFile(record);
            return file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /** 校验数据库里的路径确实位于该任务的 tar 目录，避免脏数据或符号链接读到目录外文件。 */
    private File readyFile(ImageDownloadRecord record) throws IOException {
        Path expectedDir = localTarDir(record.getLogId()).toPath().toAbsolutePath().normalize();
        Path target = new File(record.getPath()).toPath().toAbsolutePath().normalize();
        Assert.state(target.getParent() != null && target.getParent().equals(expectedDir), "tar 路径不合法");
        Assert.state(!Files.isSymbolicLink(expectedDir), "tar 目录不能是符号链接");
        Assert.state(Files.isDirectory(expectedDir, LinkOption.NOFOLLOW_LINKS), "tar 目录已不存在");
        Assert.state(!Files.isSymbolicLink(target), "tar 文件不能是符号链接");
        Path realDir = expectedDir.toRealPath();
        Path realTarget = target.toRealPath();
        Assert.state(realTarget.startsWith(realDir), "tar 路径不合法");
        Assert.state(Files.isRegularFile(realTarget, LinkOption.NOFOLLOW_LINKS), "tar 文件已不存在: " + record.getPath());
        return target.toFile();
    }

    private void deleteArchiveDirQuietly(String logId) {
        try {
            deleteArchiveDir(logId);
        } catch (Exception e) {
            log.warn("删除镜像归档目录失败: logId={} {}", logId, e.getMessage());
        }
    }

    /** 按 logId 派生目录并执行不跟随符号链接的删除，供主机镜像导出链路复用。 */
    void deleteArchiveDir(String logId) throws IOException {
        Assert.isTrue(isValidLogId(logId), "非法的任务标识");
        deleteTreeNoFollow(localTarDir(logId).toPath());
    }

    private static void deletePathQuietly(Path path) {
        try {
            deleteTreeNoFollow(path);
        } catch (Exception e) {
            log.warn("删除镜像归档路径失败: {} {}", path, e.getMessage());
        }
    }

    /** 不跟随符号链接的递归删除，避免把链接指向的目录当成本任务文件清空。 */
    private static void deleteTreeNoFollow(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String shortError(Exception e) {
        return StrUtil.sub(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 0, MAX_ERROR_LENGTH);
    }

    /** 镜像去掉 tag 后的仓库名；registry 带端口时也不会误判。 */
    private static String repoOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(0, lastColon) : image;
    }

    /** 镜像 tag，未写时默认 latest。 */
    private static String tagOf(String image) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(lastColon + 1) : "latest";
    }

    /** 文件名优先取镜像的第一个 repo tag（如 nginx-1.27-alpine.tar），无标签镜像退化为 image-{短 ID}.tar。 */
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

    /** 去掉注册中心前缀，并把 : / @ 等文件名非法字符换掉。 */
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

    /** 日志里的速度后缀：同时给每秒与每分钟，例如 80 MB/s（4.7 GB/min）。 */
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
