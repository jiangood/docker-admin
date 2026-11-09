package io.github.jiangood.docker.admin.websocket;// DockerLogService.java

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DockerLogService {

    /** 下载日志时读取的末尾行数，与页面实时日志的初始 tail 保持一致。 */
    public static final int DOWNLOAD_TAIL_LINES = 2000;

    /** 按时间段查询时最多返回的行数，避免长时间范围把整段日志读进内存。 */
    public static final int RANGE_TAIL_LINES = 5000;

    private final Map<String, LogStreamCallback> sessionLogStreams = new ConcurrentHashMap<>();

    @Resource
    private DockerClientManager dockerClientManager;


    /**
     * 拉取容器日志并推送到 WebSocket。
     *
     * @param follow 是否持续跟随输出。运行中的容器传 {@code true}；已停止的容器传
     *               {@code false}，只拉取历史日志（Docker 守护进程支持读取已停止容器的日志）。
     */
    public void streamContainerLogs(String sessionId, Host host, String containerId, WebSocketSession session, boolean follow) {
        try {
            DockerClient dockerClient = dockerClientManager.getClient(host);

            LogStreamCallback callback = new LogStreamCallback(session, dockerClient);

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(follow)
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

    /**
     * 把容器日志写入输出流（只读历史，不跟随）。
     * <p>
     * {@code since}/{@code until} 为秒级时间戳，用于按时间段查询；两者任一存在时自动带 Docker 时间戳前缀，
     * 方便前端对照时间。读完即返回，调用方负责关闭输出流；DockerClient 在这里关闭，
     * 与流式日志一致，避免 SSH 会话泄漏。
     */
    public void writeContainerLogs(Host host, String containerId, Integer since, Integer until,
                                   int tail, OutputStream out) throws InterruptedException {
        DockerClient dockerClient = dockerClientManager.getClient(host);
        try {
            LogContainerCmd cmd = dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .withTail(tail)
                    .withTimestamps(since != null || until != null);
            if (since != null) {
                cmd.withSince(since);
            }
            if (until != null) {
                cmd.withUntil(until);
            }
            cmd.exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        out.write(frame.getPayload());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }).awaitCompletion();
        } finally {
            IOUtils.closeQuietly(dockerClient);
        }
    }

    /**
     * 按时间段读取容器日志（末尾最多 {@value #RANGE_TAIL_LINES} 行），返回文本供页面静态展示。
     */
    public String readContainerLogs(Host host, String containerId, Integer since, Integer until)
            throws InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeContainerLogs(host, containerId, since, until, RANGE_TAIL_LINES, buffer);
        return buffer.toString(StandardCharsets.UTF_8);
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
