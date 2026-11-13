package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.openadmin.modules.task.repository.SysTaskLogRepository;
import io.github.jiangood.openadmin.modules.task.entity.SysTaskLog;
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
 * 通用任务日志 WebSocket（{@code /admin/ws/task-log/{logId}}）握手鉴权。
 * <p>
 * 与其他 {@code *LogHandshakeInterceptor} 相同的原因：{@code afterConnectionEstablished}
 * 执行时已脱离 HTTP 请求线程、没有 Spring Security 上下文，权限校验必须放在握手拦截器中完成。
 * <p>
 * 鉴权依据是任务记录自身的 {@code permission} 字段（任务登记时写入），
 * 并要求 logId 必须对应一条任务记录——只靠 logId 格式白名单防目录穿越不够，
 * 这里再挡掉「猜一个已存在的日志文件名」的访问。
 */
@Slf4j
@Component
public class TaskLogHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 仅允许任务生成的 logId（类型前缀-随机串，或各业务的记录 id）。
     */
    public static final Pattern LOG_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Resource
    private SysTaskLogRepository taskLogRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String logId = path.substring(path.lastIndexOf('/') + 1);
        if (!LOG_ID_PATTERN.matcher(logId).matches()) {
            log.warn("任务日志 WebSocket 握手被拒绝，非法 logId: {}", logId);
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        SysTaskLog task = taskLogRepository.findByLogId(logId).orElse(null);
        if (task == null) {
            log.warn("任务日志 WebSocket 握手被拒绝，任务记录不存在: {}", logId);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!hasPermission(task.getPermission())) {
            log.warn("任务日志 WebSocket 握手被拒绝，缺少权限或未登录（permission={}，logId={}）", task.getPermission(), logId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    private boolean hasPermission(String permission) {
        LoginUser user = LoginTool.getUser();
        if (user == null) {
            return false;
        }
        // permission 为空表示仅需登录用户即可查看
        if (permission == null || permission.isBlank()) {
            return true;
        }
        Set<String> permissions = user.getPermissions();
        return permissions != null && (permissions.contains("*") || permissions.contains(permission));
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}
