package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.collection.CollUtil;
import io.github.jiangood.docker.config.Config;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * 默认仅放行本机来源：开发时前端(8090)代理到后端(8080)属于跨域，需要放行；
     * 生产前后端同源，Spring 会自动放行同源请求。如需其他域名，配置 {@code cfg.ws-origins}。
     */
    private static final String[] DEFAULT_ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:*", "http://127.0.0.1:*",
            "https://localhost:*", "https://127.0.0.1:*"
    };

    @Resource
    private ContainerLogWebSocketHandler handler;

    @Resource
    private ContainerLogHandshakeInterceptor handshakeInterceptor;

    @Resource
    private SyncLogWebSocketHandler syncLogHandler;

    @Resource
    private SyncLogHandshakeInterceptor syncLogHandshakeInterceptor;

    @Resource
    private BuildTestLogHandshakeInterceptor buildTestLogHandshakeInterceptor;

    @Resource
    private ImageBuildLogHandshakeInterceptor imageBuildLogHandshakeInterceptor;

    @Resource
    private TunnelLogHandshakeInterceptor tunnelLogHandshakeInterceptor;

    @Resource
    private Config config;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = CollUtil.isNotEmpty(config.getWsOrigins())
                ? config.getWsOrigins().toArray(new String[0])
                : DEFAULT_ALLOWED_ORIGIN_PATTERNS;

        // 容器实时日志
        registry.addHandler(handler, "/admin/ws/log/{id}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(origins);

        // 镜像同步实时日志
        registry.addHandler(syncLogHandler, "/admin/ws/sync-log/{logId}")
                .addInterceptors(syncLogHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);

        // 构建测试实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/build-test-log/{logId}")
                .addInterceptors(buildTestLogHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);

        // 镜像构建实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/image-build-log/{logId}")
                .addInterceptors(imageBuildLogHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);

        // 隧道任务实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/tunnel-log/{logId}")
                .addInterceptors(tunnelLogHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);
    }


}
