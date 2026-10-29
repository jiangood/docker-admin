package io.github.jiangood.docker.admin.websocket;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册。
 * <p>
 * <b>不做 Origin（来源）校验</b>，也不调 {@code setAllowedOriginPatterns}：一旦设了白名单，Spring 会挂上
 * {@code OriginHandshakeInterceptor}，它按 {@code request.getScheme()/getServerName()/getServerPort()} 判同源
 * （见 {@code WebUtils.isSameOrigin}，不看 {@code X-Forwarded-*}）。平台可能经 nginx 或 http-tunnel 隧道访问，
 * 后端看到的是 {@code http} + 内网端口 8601，与浏览器的 {@code Origin: https://域名} 协议、端口都对不上，
 * 握手会被判成跨域直接回 403（浏览器侧 {@code Unexpected response code: 403}）。
 * <p>
 * 不设白名单就等于放行所有来源，跨站防护改由两件事承担：
 * <ul>
 *     <li>会话 Cookie 显式 {@code SameSite=Lax}（{@code application.yml}），跨站页面的 WebSocket 握手不带 Cookie；</li>
 *     <li>每个端点都必须挂上鉴权拦截器（登录 + 权限 + 数据权限），见 {@code *HandshakeInterceptor}。</li>
 * </ul>
 * 因此<b>新增端点不要漏掉鉴权拦截器</b>，也不要把 Cookie 改成 {@code SameSite=None}。
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
    private HostComposeLogHandshakeInterceptor hostComposeLogHandshakeInterceptor;

    @Resource
    private DeployLogHandshakeInterceptor deployLogHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 容器实时日志
        registry.addHandler(handler, "/admin/ws/log/{id}")
                .addInterceptors(handshakeInterceptor);

        // 通用容器实时日志（主机 + 容器维度）
        registry.addHandler(genericContainerLogHandler, "/admin/ws/container-log/{hostId}/{containerId}")
                .addInterceptors(genericContainerLogHandshakeInterceptor);

        // 容器交互式控制台
        registry.addHandler(containerExecHandler, "/admin/ws/container-exec/{hostId}/{containerId}")
                .addInterceptors(containerExecHandshakeInterceptor);

        // 镜像同步实时日志
        registry.addHandler(syncLogHandler, "/admin/ws/sync-log/{logId}")
                .addInterceptors(syncLogHandshakeInterceptor);

        // 构建测试实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/build-test-log/{logId}")
                .addInterceptors(buildTestLogHandshakeInterceptor);

        // 镜像构建实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/build-log/{logId}")
                .addInterceptors(buildLogHandshakeInterceptor);

        // 镜像导出实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/image-export-log/{logId}")
                .addInterceptors(imageExportLogHandshakeInterceptor);

        // 镜像导入实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/image-import-log/{logId}")
                .addInterceptors(imageImportLogHandshakeInterceptor);

        // 镜像下载 tar 实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/image-download-log/{logId}")
                .addInterceptors(imageDownloadLogHandshakeInterceptor);

        // 主机详情页镜像导出实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/host-image-export-log/{logId}")
                .addInterceptors(hostImageExportLogHandshakeInterceptor);

        // 主机详情页 Compose 命令实时日志（复用同一日志 tail 处理器）
        registry.addHandler(syncLogHandler, "/admin/ws/host-compose-log/{logId}")
                .addInterceptors(hostComposeLogHandshakeInterceptor);

        // 部署实时日志（复用同一日志 tail 处理器，logId 即部署记录 id）
        registry.addHandler(syncLogHandler, "/admin/ws/deploy-log/{logId}")
                .addInterceptors(deployLogHandshakeInterceptor);
    }

}
