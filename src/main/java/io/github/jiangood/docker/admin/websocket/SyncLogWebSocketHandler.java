package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.service.ImageSyncService;
import io.github.jiangood.openadmin.util.SpringTool;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 镜像同步实时日志 WebSocket。
 * <p>
 * 连接后先把日志文件已有内容补齐（避免错过 WS 建立前的日志），再增量 tail，
 * 任务结束并写完最后内容后关闭连接。数据来源即 {@code /data/logs/{logId}.log}。
 */
@Slf4j
@Component
public class SyncLogWebSocketHandler extends TextWebSocketHandler {

    /**
     * 增量读取间隔（毫秒）。
     */
    private static final long POLL_INTERVAL = 300L;

    /**
     * 任务结束后等待最后刷盘的宽限次数。
     */
    private static final int END_GRACE_TIMES = 3;

    @Resource
    private ImageSyncService imageSyncService;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String logId = StrUtil.subAfter(session.getUri().getPath(), "/", true);
        executorService.submit(() -> streamLog(session, logId));
    }

    private void streamLog(WebSocketSession session, String logId) {
        File file = new File(logPath(), logId + ".log");
        int sent = 0;
        int grace = 0;
        try {
            while (session.isOpen()) {
                String content = file.exists() ? FileUtil.readUtf8String(file) : "";
                // 只发送到最后一个换行，避免把未写完的多字节字符截断
                int upto = content.lastIndexOf('\n') + 1;
                if (upto > sent) {
                    session.sendMessage(new TextMessage(content.substring(sent, upto)));
                    sent = upto;
                }

                if (imageSyncService.isRunning(logId)) {
                    grace = 0;
                } else {
                    grace++;
                    if (grace >= END_GRACE_TIMES) {
                        if (content.length() > sent) {
                            session.sendMessage(new TextMessage(content.substring(sent)));
                        }
                        session.sendMessage(new TextMessage("\n[同步任务已结束]"));
                        break;
                    }
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("同步日志推送终止: {}", e.getMessage());
        } finally {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception e) {
                log.debug("关闭同步日志会话失败: {}", e.getMessage());
            }
        }
    }

    private static String logPath() {
        String path = SpringTool.getProperty("logging.file.path");
        return StrUtil.isBlank(path) ? "/data/logs" : path;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

}
