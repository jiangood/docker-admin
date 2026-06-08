package io.github.jiangood.docker.admin.websocket;// ContainerLogWebSocketHandler.java

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.service.AppService;
import jakarta.annotation.Resource;
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

@Component
public class ContainerLogWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private DockerLogService dockerLogService;

    @Resource
    private AppService appService;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newCachedThreadPool();


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        System.out.println("WebSocket连接建立: " + session.getId());
        session.sendMessage(new TextMessage("连接成功"));
        String path = session.getUri().getPath();
        String id = StrUtil.subAfter(path, "/", true);
        App app = appService.findById(id).orElse(null);
        String dockerHost = app.getHost().getDockerHost();
        Container container = appService.getContainer(app);
        if (container == null || container.getStatus().equals("exited")) {
            // 发送容器状态
            session.sendMessage(new TextMessage("容器已退出"));
            return;
        }
        String containerId = container.getId();

        executorService.submit(() -> {
            try {
                dockerLogService.streamContainerLogs(session.getId(), dockerHost, containerId, session);
            } catch (Exception e) {
                try {
                    session.sendMessage(new TextMessage("执行容器日志命令失败" + e.getMessage()));
                } catch (IOException ex) {
                    ex.getMessage();
                }
            }
        });
    }



    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws IOException {
        sessions.remove(session.getId());
        dockerLogService.stopAllLogsForSession(session.getId());
        System.out.println("WebSocket连接关闭: " + session.getId());
    }


}
