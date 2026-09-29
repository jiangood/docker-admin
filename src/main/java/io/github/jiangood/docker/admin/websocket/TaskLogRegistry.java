package io.github.jiangood.docker.admin.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中的后台任务日志注册表：WebSocket 日志推送据此判断任务是否已结束。
 * <p>
 * 镜像同步、构建测试等「不落库」的异步任务共用。
 */
@Component
public class TaskLogRegistry {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public void start(String logId) {
        if (logId != null) {
            running.add(logId);
        }
    }

    public void finish(String logId) {
        if (logId != null) {
            running.remove(logId);
        }
    }

    public boolean isRunning(String logId) {
        return logId != null && running.contains(logId);
    }

}
