package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.util.ContainerPermTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 主机 Docker CLI WebSocket 握手鉴权：
 * {@code /admin/ws/host-cli/{hostId}}。
 * <p>
 * 该终端等价于宿主机 root（辅助容器挂载了 docker.sock），需要 {@code host:cli} 权限。
 */
@Slf4j
@Component
public class HostCliHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_HOST_ID = "hostCliHostId";

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

        attributes.put(ATTR_HOST_ID, hostId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
