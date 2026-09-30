package io.github.jiangood.docker.admin.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中的后台任务日志注册表：WebSocket 日志推送据此判断任务是否已结束。
 * <p>
 * 镜像同步、构建测试等「不落库」的异步任务共用。
 * <p>
 * 任务若把底层的可中断资源（如 docker-java 的 {@code DefaultCallback}）通过
 * {@link #register(String, Closeable)} 登记进来，即可用 {@link #cancel(String)} 关闭响应流中断任务。
 */
@Slf4j
@Component
public class TaskLogRegistry {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * logId -> 任务底层的可中断资源。只在可中断的阶段（如 docker 构建、拉取）存在，
     * 任务结束或取消后移除。
     */
    private final Map<String, Closeable> cancellables = new ConcurrentHashMap<>();

    public void start(String logId) {
        if (logId != null) {
            running.add(logId);
        }
    }

    /**
     * 登记任务底层的可中断资源，供 {@link #cancel(String)} 关闭。
     */
    public void register(String logId, Closeable cancellable) {
        if (logId != null && cancellable != null) {
            cancellables.put(logId, cancellable);
        }
    }

    /**
     * 中断任务：关闭底层响应流。
     *
     * @return false 表示当前没有可中断的资源（任务未开始、已结束，或正处于无法中断的阶段，如克隆代码）
     */
    public boolean cancel(String logId) {
        if (logId == null) {
            return false;
        }
        Closeable cancellable = cancellables.remove(logId);
        if (cancellable == null) {
            return false;
        }
        try {
            cancellable.close();
        } catch (IOException e) {
            log.debug("关闭任务资源失败: {}", e.getMessage());
        }
        return true;
    }

    public void finish(String logId) {
        if (logId != null) {
            running.remove(logId);
            cancellables.remove(logId);
        }
    }

    public boolean isRunning(String logId) {
        return logId != null && running.contains(logId);
    }

}
