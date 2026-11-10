package io.github.jiangood.docker.admin.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * 主机 Docker CLI WebSocket：{@code /admin/ws/host-cli/{hostId}?session=<token>}。
 * <p>
 * 在辅助容器内打开 {@code /bin/sh}，容器内的 {@code docker} 命令通过挂载的宿主机
 * docker.sock 操作宿主机 daemon；宿主机目录挂在 {@code /host}。
 * <p>
 * 与容器控制台不同，这里的 shell 由 {@link HostCliSessionRegistry} 持有，WebSocket 只是
 * 「订阅者」：断开连接不会结束命令，重连会把断开期间的输出回放出来。
 * <p>
 * 前端 xterm 的消息格式与容器控制台一致：
 * {@code {"type":"input","data":"<base64>"}} / {@code {"type":"resize","cols","rows"}}，
 * 另有 {@code {"type":"ping"}} 心跳（仅用于穿透代理空闲超时，不产生输出）与
 * {@code {"type":"close"}}（用户点「新建会话」时主动结束当前 shell）。
 */
@Slf4j
@Component
public class HostCliWebSocketHandler extends TextWebSocketHandler {

    /** 把会话挂到 WebSocket 属性上，关闭时按连接找到对应会话。 */
    static final String ATTR_SESSION = "hostCliSession";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 回放按块下发，避免一次几十万字节的 WebSocket 帧把浏览器卡住。 */
    private static final int REPLAY_CHUNK_BYTES = 16 * 1024;

    /**
     * 注意字段名不能叫 {@code sessionRegistry}：open-admin 继承了 Spring Security，
     * 容器里已有一个同名 Bean {@code SessionRegistryImpl}，{@code @Resource} 会按名字优先注入而报类型不匹配。
     */
    @Resource
    private HostCliSessionRegistry cliSessionRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        String hostId = (String) attrs.get(HostCliHandshakeInterceptor.ATTR_HOST_ID);
        String userId = (String) attrs.get(HostCliHandshakeInterceptor.ATTR_USER_ID);
        String token = (String) attrs.get(HostCliHandshakeInterceptor.ATTR_SESSION_TOKEN);

        try {
            HostCliSession cli = cliSessionRegistry.getOrCreate(hostId, userId, token);
            attrs.put(ATTR_SESSION, cli);
            byte[] replay = cli.attach(session);
            sendReplay(session, replay);
            cli.markReady(session);
        } catch (Exception e) {
            log.error("打开主机命令行失败", e);
            sendText(session, "打开主机命令行失败: " + e.getMessage()
                    + "\r\n请先在主机详情页部署辅助容器。");
            closeQuietly(session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        HostCliSession cli = sessionOf(session);
        if (cli == null) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("input".equals(type)) {
                byte[] data = Base64.getDecoder().decode(node.path("data").asText(""));
                cli.write(data);
            } else if ("resize".equals(type)) {
                cli.resize(node.path("cols").asInt(80), node.path("rows").asInt(24));
            } else if ("ping".equals(type)) {
                cli.touch();
            } else if ("close".equals(type)) {
                // 用户在界面上点「新建会话」：结束当前 shell，前端随后重连会拿到新的
                cliSessionRegistry.closeSession(cli);
                closeQuietly(session);
            }
        } catch (Exception e) {
            log.debug("处理主机命令行消息失败: {}", e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        HostCliSession cli = sessionOf(session);
        if (cli == null) {
            return;
        }
        try {
            byte[] data = new byte[message.getPayload().remaining()];
            message.getPayload().get(data);
            cli.write(data);
        } catch (Exception e) {
            log.debug("处理主机命令行二进制消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        HostCliSession cli = sessionOf(session);
        if (cli != null) {
            // 只取消订阅：shell 继续跑，输出继续进缓冲，等待重连
            cli.detach(session);
        }
    }

    private static HostCliSession sessionOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_SESSION);
        return value instanceof HostCliSession cli ? cli : null;
    }

    private static void sendReplay(WebSocketSession session, byte[] replay) {
        if (replay == null || replay.length == 0) {
            return;
        }
        for (int offset = 0; offset < replay.length; offset += REPLAY_CHUNK_BYTES) {
            int length = Math.min(REPLAY_CHUNK_BYTES, replay.length - offset);
            byte[] chunk = offset == 0 && length == replay.length
                    ? replay
                    : Arrays.copyOfRange(replay, offset, offset + length);
            sendBinary(session, chunk);
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
