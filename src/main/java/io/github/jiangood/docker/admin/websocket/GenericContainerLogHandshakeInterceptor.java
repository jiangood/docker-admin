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
 * 通用容器日志 WebSocket 握手鉴权：{@code /admin/ws/container-log/{hostId}/{containerId}}。
 * <p>
 * 与 {@link ContainerLogHandshakeInterceptor}（应用维度）相对，这里按主机 + 容器定位，
 * 供主机详情页的容器组件使用。只读能力复用 host:list / app:view。
 */
@Slf4j
@Component
public class GenericContainerLogHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_HOST_ID = "containerLogHostId";
    static final String ATTR_CONTAINER_ID = "containerLogContainerId";

    @Resource
    private HostService hostService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String[] parts = WebSocketPathUtils.segmentsAfter(request.getURI().getPath(), "container-log");
        if (parts == null || parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String hostId = parts[0];
        String containerId = parts[1];

        if (!ContainerPermTool.canView()) {
            log.warn("容器日志握手被拒绝，缺少查看权限: host={} container={}", hostId, containerId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        Host host = hostService.findById(hostId).orElse(null);
        if (host == null) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        attributes.put(ATTR_HOST_ID, hostId);
        attributes.put(ATTR_CONTAINER_ID, containerId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
