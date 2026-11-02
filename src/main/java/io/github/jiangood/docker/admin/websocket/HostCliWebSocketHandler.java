package io.github.jiangood.docker.admin.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.service.HostAgentService;
import io.github.jiangood.docker.admin.service.HostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主机 Docker CLI WebSocket：{@code /admin/ws/host-cli/{hostId}}。
 * <p>
 * 在辅助容器内打开 {@code /bin/sh}，容器内的 {@code docker} 命令通过挂载的宿主机
 * docker.sock 操作宿主机 daemon；宿主机目录挂在 {@code /host}。
 * <p>
 * 前端 xterm 的消息格式与容器控制台一致：
 * {@code {"type":"input","data":"<base64>"}} / {@code {"type":"resize","cols","rows"}}。
 */
@Slf4j
@Component
public class HostCliWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private HostService hostService;

    @Resource
    private HostAgentService hostAgentService;

    @Resource
    private ContainerService containerService;

    private final Map<String, ContainerService.ExecSession> execSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String hostId = (String) session.getAttributes().get(HostCliHandshakeInterceptor.ATTR_HOST_ID);
        Host host = hostService.findById(hostId).orElse(null);
        if (host == null) {
            sendText(session, "主机不存在");
            closeQuietly(session);
            return;
        }

        try {
            HostAgentService.AgentContext context = hostAgentService.requireRunning(host);
            String banner = "已进入主机辅助容器 " + HostAgentService.CONTAINER_NAME + "\r\n"
                    + "宿主机目录 " + context.root() + " 在容器内保持相同路径（当前工作目录）\r\n"
                    + "docker / docker compose 命令已指向宿主机 daemon。\r\n"
                    + "该终端权限等同于宿主机 root，请谨慎操作。\r\n\r\n";
            sendText(session, banner);

            ContainerService.ExecSession exec = containerService.openExecSession(host, context.containerId(),
                    "/bin/sh",
                    bytes -> sendBinary(session, bytes),
                    () -> closeQuietly(session));
            execSessions.put(session.getId(), exec);
        } catch (Exception e) {
            log.error("打开主机命令行失败", e);
            sendText(session, "打开主机命令行失败: " + e.getMessage()
                    + "\r\n请先在主机详情页部署辅助容器。");
            closeQuietly(session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ContainerService.ExecSession exec = execSessions.get(session.getId());
        if (exec == null) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("input".equals(type)) {
                byte[] data = Base64.getDecoder().decode(node.path("data").asText(""));
                if (data.length > 0) {
                    exec.write(data);
                }
            } else if ("resize".equals(type)) {
                exec.resize(node.path("cols").asInt(80), node.path("rows").asInt(24));
            }
        } catch (Exception e) {
            log.debug("处理主机命令行消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ContainerService.ExecSession exec = execSessions.remove(session.getId());
        if (exec != null) {
            exec.close();
        }
    }

    private static void sendBinary(WebSocketSession session, byte[] payload) {
        if (payload == null || payload.length == 0 || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new BinaryMessage(payload));
            }
        } catch (Exception e) {
            log.debug("发送主机命令行输出失败: {}", e.getMessage());
        }
    }

    private static void sendText(WebSocketSession session, String text) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            log.debug("发送主机命令行提示失败: {}", e.getMessage());
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
