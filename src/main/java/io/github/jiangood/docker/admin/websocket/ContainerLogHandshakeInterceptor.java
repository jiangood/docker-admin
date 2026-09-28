package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.base.OrgAccessTool;
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

import java.util.Map;
import java.util.Set;

/**
 * 容器日志 WebSocket 握手鉴权。
 * <p>
 * {@code afterConnectionEstablished} 执行时已脱离 HTTP 请求线程、没有 Spring Security 上下文，
 * 因此权限校验必须放在握手拦截器（仍在请求线程）中完成：校验 {@code app:view} 权限与组织数据权限。
 */
@Slf4j
@Component
public class ContainerLogHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手成功后写入会话的属性：允许访问的应用 id。
     */
    static final String ATTR_APP_ID = "containerLogAppId";

    @Resource
    private AppService appService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String id = StrUtil.subAfter(request.getURI().getPath(), "/", true);
        App app = appService.findById(id).orElse(null);
        if (app == null) {
            log.warn("WebSocket 握手失败，应用不存在: {}", id);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!hasAppViewPermission()) {
            log.warn("WebSocket 握手被拒绝，缺少 app:view 权限: {}", id);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        if (!OrgAccessTool.canAccess(app.getSysOrg())) {
            log.warn("WebSocket 握手被拒绝，无组织数据权限: {}", id);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ATTR_APP_ID, app.getId());
        return true;
    }

    private boolean hasAppViewPermission() {
        LoginUser user = LoginTool.getUser();
        if (user == null) {
            return false;
        }
        Set<String> permissions = user.getPermissions();
        return permissions != null && (permissions.contains("*") || permissions.contains("app:view"));
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
