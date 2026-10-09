package io.github.jiangood.docker.admin.websocket;// ContainerLogWebSocketHandler.java

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.AppService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class ContainerLogWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private DockerLogService dockerLogService;

    @Resource
    private AppService appService;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("WebSocket连接建立: {}", session.getId());
        session.sendMessage(new TextMessage("连接成功"));
        String path = session.getUri().getPath();
        String id = StrUtil.subAfter(path, "/", true);
        App app = appService.findById(id).orElse(null);
        if (app == null) {
            session.sendMessage(new TextMessage("应用不存在"));
            session.close(CloseStatus.NORMAL);
            return;
        }
        Host host = app.getHost();
        Container container = appService.getContainer(app);
        if (container == null) {
            session.sendMessage(new TextMessage("容器未部署"));
            return;
        }
        String containerId = container.getId();
        // 已停止（exited/created/dead 等）的容器同样可以读取历史日志，只是不再跟随输出
        boolean running = "running".equalsIgnoreCase(container.getState());

        executorService.submit(() -> {
            try {
                if (!running) {
                    session.sendMessage(new TextMessage("容器当前状态：" + container.getState() + "，以下为历史日志"));
                }
                dockerLogService.streamContainerLogs(session.getId(), host, containerId, session, running);
            } catch (Exception e) {
                log.error("执行容器日志命令失败", e);
                try {
                    session.sendMessage(new TextMessage("执行容器日志命令失败" + e.getMessage()));
                } catch (IOException ex) {
                    log.debug("发送错误提示失败", ex);
                }
            }
        });
    }



    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws IOException {
        sessions.remove(session.getId());
        dockerLogService.stopAllLogsForSession(session.getId());
        log.info("WebSocket连接关闭: {}", session.getId());
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

}
