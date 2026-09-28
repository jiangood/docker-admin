package io.github.jiangood.docker.admin.websocket;// DockerLogService.java

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DockerLogService {

    private final Map<String, LogStreamCallback> sessionLogStreams = new ConcurrentHashMap<>();

    @Resource
    private DockerClientManager dockerClientManager;


    public void streamContainerLogs(String sessionId, Host host, String containerId, WebSocketSession session) {
        try {
            DockerClient dockerClient = dockerClientManager.getClient(host);

            LogStreamCallback callback = new LogStreamCallback(session, dockerClient);

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTail(2000)
                    .exec(callback);

            sessionLogStreams.put(sessionId, callback);

            // 发送开始消息
            session.sendMessage(new TextMessage("开始监听容器日志: " + containerId));
        } catch (Exception e) {
            log.error("启动容器日志流失败", e);
            throw new RuntimeException("无法启动日志流: " + e.getMessage());
        }
    }

    public void stopAllLogsForSession(String sessionId) throws IOException {
        LogStreamCallback callback = sessionLogStreams.remove(sessionId);
        if (callback != null) {
            callback.close();
        }
    }

    private static class LogStreamCallback extends ResultCallback.Adapter<Frame> {
        private final WebSocketSession session;
        private final DockerClient dockerClient;

        public LogStreamCallback(WebSocketSession session, DockerClient dockerClient) {
            this.session = session;
            this.dockerClient = dockerClient;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            String message = new String(payload);

            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.error("通过 WebSocket 发送容器日志失败", e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                // 关闭底层 docker 客户端，避免 SSH 会话泄漏
                IOUtils.closeQuietly(dockerClient);
            }
        }
    }
}
