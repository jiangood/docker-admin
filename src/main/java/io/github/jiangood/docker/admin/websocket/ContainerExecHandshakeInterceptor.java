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
 * 容器控制台 WebSocket 握手鉴权：
 * {@code /admin/ws/container-exec/{hostId}/{containerId}?shell=/bin/bash}。
 * <p>
 * 控制台等价于在目标主机容器内执行任意命令，属于高风险能力，需要 {@code container:exec} 权限。
 */
@Slf4j
@Component
public class ContainerExecHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_HOST_ID = "containerExecHostId";
    static final String ATTR_CONTAINER_ID = "containerExecContainerId";
    static final String ATTR_SHELL = "containerExecShell";

    @Resource
    private HostService hostService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String[] parts = WebSocketPathUtils.segmentsAfter(request.getURI().getPath(), "container-exec");
        if (parts == null || parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String hostId = parts[0];
        String containerId = parts[1];

        if (!ContainerPermTool.has(ContainerPermTool.PERM_EXEC)) {
            log.warn("容器控制台握手被拒绝，缺少 container:exec 权限: host={} container={}", hostId, containerId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        Host host = hostService.findById(hostId).orElse(null);
        if (host == null) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        String shell = null;
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "shell".equals(pair.substring(0, eq))) {
                    shell = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }

        attributes.put(ATTR_HOST_ID, hostId);
        attributes.put(ATTR_CONTAINER_ID, containerId);
        attributes.put(ATTR_SHELL, shell);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
