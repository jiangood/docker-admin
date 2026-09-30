package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.config.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 隧道任务日志 WebSocket 握手鉴权。
 * <p>
 * 与镜像同步日志同理：{@code afterConnectionEstablished} 时已无 Spring Security 上下文，
 * 因此权限校验必须在握手拦截器（仍在请求线程）中完成：校验 {@code tunnel:view} 权限，
 * 并限制 logId 取值以防目录穿越。
 */
@Slf4j
@Component
public class TunnelLogHandshakeInterceptor implements HandshakeInterceptor {

    static final Pattern LOG_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String logId = path.substring(path.lastIndexOf('/') + 1);
        if (!LOG_ID_PATTERN.matcher(logId).matches()) {
            log.warn("隧道日志 WebSocket 握手被拒绝，非法 logId: {}", logId);
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (!hasViewPermission()) {
            log.warn("隧道日志 WebSocket 握手被拒绝，缺少 tunnel:view 权限");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    private boolean hasViewPermission() {
        LoginUser user = LoginTool.getUser();
        if (user == null) {
            return false;
        }
        Set<String> permissions = user.getPermissions();
        return permissions != null && (permissions.contains("*") || permissions.contains("tunnel:view"));
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

}
