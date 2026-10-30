package io.github.jiangood.docker.admin.websocket;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 端点注册。
 * <p>
 * <b>每个端点都必须显式 {@code setAllowedOriginPatterns("*")}</b>（统一走 {@link #register}）：
 * <ul>
 *     <li>Spring 的 {@code AbstractWebSocketHandlerRegistration#getInterceptors()} <b>无条件</b>追加
 *     {@code OriginHandshakeInterceptor}——「不调 setAllowedOriginPatterns」并不等于关闭 Origin 校验；</li>
 *     <li>它的 allowedOrigins / allowedOriginPatterns 默认是空集合，{@code CorsConfiguration.checkOrigin}
 *     对空配置一律返回 null，实际效果是<b>只允许严格同源</b>；只有显式配置 {@code "*"} 才是放行所有来源；</li>
 *     <li>同源判定见 {@code WebUtils.isSameOrigin}，直接取 {@code getScheme() / getServerName() / getServerPort()}，
 *     不看 {@code X-Forwarded-*}。平台通常经 nginx 或 http-tunnel 隧道终止 TLS，后端看到的是 {@code http} + 内网端口，
 *     与浏览器的 {@code Origin: https://域名}（443）协议、端口都对不上，握手被判跨域直接回 403
 *     （浏览器侧 {@code Unexpected response code: 403}；会话失效是 401，别混淆）。</li>
 * </ul>
 * 跨站防护由两件事承担：
 * <ul>
 *     <li>会话 Cookie 显式 {@code SameSite=Lax}（{@code application.yml}），跨站页面发起的 WebSocket 握手不带 Cookie；</li>
 *     <li>每个端点都必须挂上鉴权拦截器（登录 + 权限 + 数据权限），见 {@code *HandshakeInterceptor}。</li>
 * </ul>
 * 因此<b>新增端点不要漏掉鉴权拦截器</b>，也不要漏掉放行来源，更不要把 Cookie 改成 {@code SameSite=None}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private ContainerLogWebSocketHandler handler;

    @Resource
    private ContainerLogHandshakeInterceptor handshakeInterceptor;

    @Resource
    private GenericContainerLogWebSocketHandler genericContainerLogHandler;

    @Resource
    private GenericContainerLogHandshakeInterceptor genericContainerLogHandshakeInterceptor;

    @Resource
    private ContainerExecWebSocketHandler containerExecHandler;

    @Resource
    private ContainerExecHandshakeInterceptor containerExecHandshakeInterceptor;

    @Resource
    private SyncLogWebSocketHandler syncLogHandler;

    @Resource
    private SyncLogHandshakeInterceptor syncLogHandshakeInterceptor;

    @Resource
    private BuildTestLogHandshakeInterceptor buildTestLogHandshakeInterceptor;

    @Resource
    private BuildLogHandshakeInterceptor buildLogHandshakeInterceptor;

    @Resource
    private ImageExportLogHandshakeInterceptor imageExportLogHandshakeInterceptor;

    @Resource
    private ImageImportLogHandshakeInterceptor imageImportLogHandshakeInterceptor;

    @Resource
    private ImageDownloadLogHandshakeInterceptor imageDownloadLogHandshakeInterceptor;

    @Resource
    private HostImageExportLogHandshakeInterceptor hostImageExportLogHandshakeInterceptor;

    @Resource
    private DeployLogHandshakeInterceptor deployLogHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 容器实时日志
        register(registry, handler, "/admin/ws/log/{id}", handshakeInterceptor);

        // 通用容器实时日志（主机 + 容器维度）
        register(registry, genericContainerLogHandler, "/admin/ws/container-log/{hostId}/{containerId}",
                genericContainerLogHandshakeInterceptor);

        // 容器交互式控制台
        register(registry, containerExecHandler, "/admin/ws/container-exec/{hostId}/{containerId}",
                containerExecHandshakeInterceptor);

        // 镜像同步实时日志
        register(registry, syncLogHandler, "/admin/ws/sync-log/{logId}", syncLogHandshakeInterceptor);

        // 构建测试实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/build-test-log/{logId}", buildTestLogHandshakeInterceptor);

        // 镜像构建实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/build-log/{logId}", buildLogHandshakeInterceptor);

        // 镜像导出实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/image-export-log/{logId}", imageExportLogHandshakeInterceptor);

        // 镜像导入实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/image-import-log/{logId}", imageImportLogHandshakeInterceptor);

        // 镜像下载 tar 实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/image-download-log/{logId}", imageDownloadLogHandshakeInterceptor);

        // 主机详情页镜像导出实时日志（复用同一日志 tail 处理器）
        register(registry, syncLogHandler, "/admin/ws/host-image-export-log/{logId}", hostImageExportLogHandshakeInterceptor);

        // 部署实时日志（复用同一日志 tail 处理器，logId 即部署记录 id）
        register(registry, syncLogHandler, "/admin/ws/deploy-log/{logId}", deployLogHandshakeInterceptor);
    }

    /**
     * 注册端点：鉴权拦截器 + 显式放行所有来源。
     * <p>
     * {@code "*"} 不能省——Spring 在未配置白名单时仍会挂 {@code OriginHandshakeInterceptor}，
     * 且空配置等价于只允许严格同源，经 https 反向代理访问必然 403。
     */
    private void register(WebSocketHandlerRegistry registry, WebSocketHandler handler,
                          String pattern, HandshakeInterceptor interceptor) {
        registry.addHandler(handler, pattern)
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }

}
