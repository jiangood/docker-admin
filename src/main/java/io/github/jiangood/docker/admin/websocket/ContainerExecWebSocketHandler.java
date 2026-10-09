package io.github.jiangood.docker.admin.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.ContainerService;
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
 * 容器交互式控制台 WebSocket：
 * {@code /admin/ws/container-exec/{hostId}/{containerId}?shell=/bin/bash}。
 * <p>
 * 前端 xterm 发送 JSON 文本消息：
 * <ul>
 *     <li>{@code {"type":"input","data":"<base64>"}}：键盘输入</li>
 *     <li>{@code {"type":"resize","cols":80,"rows":24}}：窗口尺寸</li>
 * </ul>
 * 容器输出以二进制帧下发，前端直接写入 xterm。
 */
@Slf4j
@Component
public class ContainerExecWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private HostService hostService;

    @Resource
    private ContainerService containerService;

    private final Map<String, ContainerService.ExecSession> execSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        String hostId = (String) attrs.get(ContainerExecHandshakeInterceptor.ATTR_HOST_ID);
        String containerId = (String) attrs.get(ContainerExecHandshakeInterceptor.ATTR_CONTAINER_ID);
        String shell = (String) attrs.get(ContainerExecHandshakeInterceptor.ATTR_SHELL);
        try {
            Host host = hostService.findById(hostId).orElse(null);
            if (host == null) {
                sendText(session, "主机不存在");
                session.close(CloseStatus.NORMAL);
                return;
            }
            ContainerService.ExecSession exec = containerService.openExecSession(host, containerId, shell,
                    bytes -> sendBinary(session, bytes),
                    () -> closeQuietly(session));
            execSessions.put(session.getId(), exec);
        } catch (Exception e) {
            log.error("打开容器控制台失败", e);
            sendText(session, "打开控制台失败: " + e.getMessage());
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
            log.debug("处理控制台消息失败: {}", e.getMessage());
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
            log.debug("发送控制台输出失败: {}", e.getMessage());
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
            log.debug("发送控制台提示失败: {}", e.getMessage());
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
