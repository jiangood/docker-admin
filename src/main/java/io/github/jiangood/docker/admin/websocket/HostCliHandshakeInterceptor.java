package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.util.ContainerPermTool;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.config.security.LoginUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 主机 Docker CLI WebSocket 握手鉴权：
 * {@code /admin/ws/host-cli/{hostId}?session=<tabToken>}。
 * <p>
 * 该终端等价于宿主机 root（辅助容器挂载了 docker.sock），需要 {@code host:cli} 权限。
 * <p>
 * {@code session} 是前端标签页生成的会话令牌，用来在断线重连后接回同一个 shell；
 * 缺省或格式非法时按一次性会话处理（行为与改造前一致）。
 */
@Slf4j
@Component
public class HostCliHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_HOST_ID = "hostCliHostId";
    static final String ATTR_USER_ID = "hostCliUserId";
    static final String ATTR_SESSION_TOKEN = "hostCliSessionToken";

    /** 只接受前端生成的短令牌，避免拼出超长 key 或奇怪字符。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Resource
    private HostService hostService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String[] parts = WebSocketPathUtils.segmentsAfter(request.getURI().getPath(), "host-cli");
        if (parts == null || parts.length < 1 || parts[0].isBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String hostId = parts[0];

        LoginUser user = LoginTool.getUser();
        if (user == null || user.getId() == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        if (!ContainerPermTool.has("host:cli")) {
            log.warn("主机命令行握手被拒绝，缺少 host:cli 权限: host={}", hostId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        Host host = hostService.findById(hostId).orElse(null);
        if (host == null) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        String token = queryParam(request.getURI().getQuery(), "session");
        if (token != null && !TOKEN_PATTERN.matcher(token).matches()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        attributes.put(ATTR_HOST_ID, hostId);
        attributes.put(ATTR_USER_ID, user.getId());
        // 不带令牌（旧前端 / 直接调用）时给一次性会话，不参与重连复用
        attributes.put(ATTR_SESSION_TOKEN, token != null ? token : UUID.randomUUID().toString());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
