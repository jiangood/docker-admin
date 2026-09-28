package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.collection.CollUtil;
import io.github.jiangood.docker.config.Config;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
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
    private Config config;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry
                .addHandler(handler, "/admin/ws/log/{id}")
                .addInterceptors(handshakeInterceptor);

        if (CollUtil.isNotEmpty(config.getWsOrigins())) {
            registration.setAllowedOriginPatterns(config.getWsOrigins().toArray(new String[0]));
        } else {
            registration.setAllowedOriginPatterns(DEFAULT_ALLOWED_ORIGIN_PATTERNS);
        }
    }


}
