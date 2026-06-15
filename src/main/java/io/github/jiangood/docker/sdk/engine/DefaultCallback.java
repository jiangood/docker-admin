package io.github.jiangood.docker.sdk.engine;

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



    public static final String TAB = "    ";

    private String logFileId;


    public DefaultCallback(String logFileId) {
        this.logFileId = logFileId;
    }



    private final CountDownLatch completed = new CountDownLatch(1);

    private Closeable stream;

    private boolean closed = false;

    private Throwable firstError = null;

    @Override
    public void onStart(Closeable stream) {
        this.stream = stream;
        this.closed = false;

        MDC.put("logFileId", this.logFileId);
    }

    @Override
    public void onError(Throwable throwable) {
        if (closed) return;

        if (this.firstError == null) {
            this.firstError = throwable;
        }

        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        MDC.remove("logFileId");
    }

    @Override
    public void onComplete() {
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
            MDC.remove("logFileId");
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


    @Override
    public void onNext(ResponseItem item) {
        if (item.isErrorIndicated()) {
            log.info("异常 {}", item.getErrorDetail().toString());

            throw new IllegalStateException(item.getErrorDetail().getMessage());
        }
        // 打印比较好的日志
        String stream = item.getStream();
        if (stream != null) {
            buffer.append(stream);

            if (stream.endsWith("\n")) {
                String info = buffer.toString();
                String[] lines = info.split("\\n|\\r");
                for (String line : lines) {
                    if (line.startsWith("Step ")) {
                        log.info(line);
                    } else {
                        log.info("{}{}", TAB, line);
                    }
                }
                buffer.setLength(0);
            }

        } else if (item.getStatus() != null) {
            log.info("{}{} {}", TAB, item.getStatus(), StrUtil.nullToEmpty(item.getProgress()));
        } else {
            log.info("{}{}", TAB, item);
        }
    }

    private final StringBuffer buffer = new StringBuffer();


}
