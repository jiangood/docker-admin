package io.github.jiangood.docker.sdk.engine;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.ResponseItem;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class DefaultCallback<T extends ResponseItem> implements ResultCallback<T> {

    private static final String TAB = "    ";

    private final String logFileId;

    private final CountDownLatch completed = new CountDownLatch(1);

    /** 流式输出的行缓冲，等待换行后再整行打印 */
    private final StringBuffer buffer = new StringBuffer();

    private Closeable stream;

    private boolean closed = false;

    private Throwable firstError = null;


    public DefaultCallback(String logFileId) {
        this.logFileId = logFileId;
    }


    @Override
    public void onStart(Closeable stream) {
        this.stream = stream;
        this.closed = false;

        MDC.put("logFileId", this.logFileId);
    }

    @Override
    public void onNext(T item) {
        if (item.isErrorIndicated()) {
            ResponseItem.ErrorDetail errorDetail = item.getErrorDetail();
            String message = errorDetail != null ? errorDetail.getMessage() : item.getError();
            log.error("{}异常 {}", TAB, message);
            throw new IllegalStateException(message);
        }

        // 构建输出等流式内容：按行输出，结尾不完整的行先缓存，等下一批数据补齐
        String stream = item.getStream();
        if (stream != null) {
            buffer.append(stream);
            if (stream.endsWith("\n")) {
                flushBuffer();
            }
            return;
        }

        // 进度输出：getProgress() 在 docker-java 3.7 已废弃，改为用 progressDetail 计算
        if (item.getStatus() != null) {
            String progress = formatProgress(item.getProgressDetail());
            if (progress.isEmpty()) {
                log.info("{}{}", TAB, item.getStatus());
            } else {
                log.info("{}{} {}", TAB, item.getStatus(), progress);
            }
            return;
        }

        log.info("{}{}", TAB, item);
    }

    @Override
    public void onError(Throwable throwable) {
        if (closed) {
            return;
        }

        if (this.firstError == null) {
            this.firstError = throwable;
        }

        try {
            flushBuffer();
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            MDC.remove("logFileId");
        }
    }

    @Override
    public void onComplete() {
        try {
            flushBuffer();
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            MDC.remove("logFileId");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                if (stream != null) {
                    stream.close();
                }
            } finally {
                completed.countDown();
            }
        }
    }


    public void awaitCompletion() throws InterruptedException {
        try {
            completed.await();
            // eventually (re)throws RuntimeException
            throwFirstError();
        } finally {
            try {
                close();
            } catch (IOException e) {
                log.debug("Failed to close", e);
            }
        }
    }


    /**
     * Throws the first occurred error as a runtime exception
     *
     * @throws com.github.dockerjava.api.exception.DockerException The first docker based Error
     * @throws RuntimeException                                    on any other occurred error
     */
    protected void throwFirstError() {
        if (firstError != null) {
            if (firstError instanceof Error) {
                throw (Error) firstError;
            }
            if (firstError instanceof RuntimeException) {
                throw (RuntimeException) firstError;
            }
            throw new RuntimeException(firstError);
        }
    }


    /**
     * 输出缓冲区中已换行的内容，空行不打印，避免日志里出现只有缩进的空白行
     */
    private void flushBuffer() {
        if (buffer.length() == 0) {
            return;
        }
        String content = buffer.toString();
        buffer.setLength(0);

        for (String line : content.split("\\r?\\n|\\r")) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            if (line.startsWith("Step ")) {
                log.info(line);
            } else {
                log.info("{}{}", TAB, line);
            }
        }
    }

    /**
     * 把 docker 的进度明细格式化成 {@code 已完成/总大小 (百分比)}，无法计算时返回空串。
     *
     * @param progressDetail 进度明细，可能为 null（docker 并非每个事件都带进度）
     */
    private String formatProgress(ResponseItem.ProgressDetail progressDetail) {
        if (progressDetail == null || progressDetail.getCurrent() == null) {
            return "";
        }
        long current = progressDetail.getCurrent();
        Long total = progressDetail.getTotal();
        if (total == null || total <= 0) {
            // 总大小未知（如部分 layer），只展示已完成量
            return FileUtil.readableFileSize(current);
        }
        long percent = Math.min(100, current * 100 / total);
        return StrUtil.format("{}/{} ({}%)",
                FileUtil.readableFileSize(current),
                FileUtil.readableFileSize(total),
                percent);
    }

}
