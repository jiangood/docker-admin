package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.dto.ContainerDetailVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.service.HostService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用容器日志 WebSocket（主机 + 容器维度），供主机详情页的容器组件使用。
 */
@Slf4j
@Component
public class GenericContainerLogWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private DockerLogService dockerLogService;

    @Resource
    private HostService hostService;

    @Resource
    private ContainerService containerService;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String hostId = (String) session.getAttributes().get(GenericContainerLogHandshakeInterceptor.ATTR_HOST_ID);
        String containerId = (String) session.getAttributes().get(GenericContainerLogHandshakeInterceptor.ATTR_CONTAINER_ID);
        executorService.submit(() -> streamLogs(session, hostId, containerId));
    }

    private void streamLogs(WebSocketSession session, String hostId, String containerId) {
        try {
            Host host = hostService.findById(hostId).orElse(null);
            if (host == null) {
                session.sendMessage(new TextMessage("主机不存在"));
                return;
            }
            boolean running = false;
            try {
                ContainerDetailVo detail = containerService.inspect(host, containerId);
                running = Boolean.TRUE.equals(detail.getRunning());
            } catch (Exception e) {
                log.debug("查询容器状态失败，按历史日志处理: {}", e.getMessage());
            }
            if (!running) {
                session.sendMessage(new TextMessage("容器当前未运行，以下为历史日志"));
            }
            dockerLogService.streamContainerLogs(session.getId(), host, containerId, session, running);
        } catch (Exception e) {
            log.error("执行容器日志命令失败", e);
            try {
                session.sendMessage(new TextMessage("执行容器日志命令失败: " + e.getMessage()));
            } catch (Exception ex) {
                log.debug("发送错误提示失败", ex);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        dockerLogService.stopAllLogsForSession(session.getId());
        super.afterConnectionClosed(session, status);
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
