package io.github.jiangood.docker.admin.websocket;// DockerLogService.java

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DockerLogService {

    private final Map<String, Map<String, LogStreamCallback>> sessionLogStreams = new ConcurrentHashMap<>();

    @Resource
    private DockerClientManager dockerClientManager;


    public void streamContainerLogs(String sessionId, String dockerHost, String containerId, WebSocketSession session) {
        try {
            DockerClient dockerClient = dockerClientManager.getClient(dockerHost);

            LogStreamCallback callback = new LogStreamCallback(session);

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTail(500)
                    .withTimestamps(false)
                    .exec(callback);

            sessionLogStreams.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                    .put(containerId, callback);

            // 发送开始消息
            session.sendMessage(new TextMessage("开始监听容器日志: " + containerId));
        } catch (Exception e) {
            log.error("启动容器日志流失败", e);
            throw new RuntimeException("无法启动日志流: " + e.getMessage());
        }
    }

    public void stopAllLogsForSession(String sessionId) {
        Map<String, LogStreamCallback> sessionStreams = sessionLogStreams.remove(sessionId);
        if (sessionStreams != null) {
            sessionStreams.forEach((containerId, callback) -> {
                try {
                    callback.close();
                } catch (IOException e) {
                    log.error("关闭日志流失败", e);
                }
            });
        }
    }

    private static class LogStreamCallback extends ResultCallback.Adapter<Frame> {
        private final WebSocketSession session;

        public LogStreamCallback(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            String message = new String(payload);

            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


    }
}
