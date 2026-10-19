package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.DeployLogRepository;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.DeployLog;
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
import java.util.regex.Pattern;

/**
 * 部署日志 WebSocket 握手鉴权。
 * <p>
 * {@code afterConnectionEstablished} 执行时已脱离 HTTP 请求线程、没有 Spring Security 上下文，
 * 因此权限校验必须放在握手拦截器（仍在请求线程）中完成：校验 {@code app:view} 权限与应用所属组织
 * 的数据权限，并限制 logId 取值以防目录穿越。
 */
@Slf4j
@Component
public class DeployLogHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 仅允许部署任务生成的 logId（即部署记录 id），同时也是日志文件名白名单，防止目录穿越。
     */
    static final Pattern LOG_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Resource
    private DeployLogRepository deployLogRepository;

    @Resource
    private AppRepository appRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String logId = path.substring(path.lastIndexOf('/') + 1);
        if (!LOG_ID_PATTERN.matcher(logId).matches()) {
            log.warn("部署日志 WebSocket 握手被拒绝，非法 logId: {}", logId);
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        DeployLog deployLog = deployLogRepository.findById(logId).orElse(null);
        if (deployLog == null) {
            log.warn("部署日志 WebSocket 握手被拒绝，部署记录不存在: {}", logId);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        if (!hasAppViewPermission()) {
            log.warn("部署日志 WebSocket 握手被拒绝，缺少 app:view 权限: {}", logId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        // 与 REST 接口一致，按部署记录所属应用的组织做数据权限校验
        App app = appRepository.findById(deployLog.getAppId()).orElse(null);
        if (app != null && !OrgAccessTool.canAccess(app.getSysOrg())) {
            log.warn("部署日志 WebSocket 握手被拒绝，无组织数据权限: {}", logId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

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
