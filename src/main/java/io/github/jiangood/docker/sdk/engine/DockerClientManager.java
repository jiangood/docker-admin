package io.github.jiangood.docker.sdk.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.mwiede.dockerjava.jsch.JschDockerHttpClient;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.base.FileLog;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Service
public class DockerClientManager {

    private static final Logger fileLog = FileLog.logger();

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3600);

    private static final int DEFAULT_SSH_PORT = 22;
    private static final String DEFAULT_SSH_USER = "root";

    /**
     * SSH 预检单条命令的超时（毫秒）。
     */
    private static final int SSH_PROBE_TIMEOUT_MILLIS = 10_000;

    /**
     * 带兜底超时调用 docker 用的执行器。
     * <p>
     * 用平台线程而不是虚拟线程：docker-java / OkHttp / JSch 内部大量 synchronized，虚拟线程会被固定到载体线程。
     * 任务在超时后会随客户端关闭而立即结束，因此不会长期占用线程。
     */
    private static final ExecutorService CALL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "docker-call");
        thread.setDaemon(true);
        return thread;
    });


    public DockerClient getClient(Host host) {
        return this.getClient(host, new Registry());
    }


    /**
     * 获取主机对应的 docker 客户端。
     * <p>
     * 每次调用都会新建连接，返回的客户端<b>由调用方负责关闭</b>。
     * <p>
     * 历史教训：这里曾对 SSH 主机做过客户端缓存，但调用方普遍在 finally 中 close，
     * 缓存里于是残留了已断开 JSch 会话的连接；第二次取到它时，请求会失败并在
     * OkHttp 关闭半连接 socket 时报
     * {@code Cannot invoke "com.jcraft.jsch.Channel.disconnect()" because "this.channel" is null}。
     * 因此不再缓存客户端——复用连接省下的 SSH 握手时间，远不值得这种难排查的串扰。
     */
    public DockerClient getClient(Host host, Registry registry) {
        return createClient(host, registry);
    }

    /**
     * 每次新建连接。调用方负责关闭，测试连接等一次性场景尤其应该用这个方法。
     */
    public DockerClient createClient(Host host, Registry registry) {
        if (host != null && host.isSsh()) {
            return buildSshClient(host, registry);
        }
        return buildTcpClient(host, registry);
    }

    /**
     * SSH 预检：在建立 docker 传输之前，先确认远端确实能执行 docker。
     * <p>
     * 为什么要预检：远端没装 docker（或 docker 命令不在 PATH 中）时，传输层执行
     * {@code docker system dial-stdio} 会立即失败，而 docker-java-transport-jsch 内部的 OkHttp
     * 把 {@code retryOnConnectionFailure} 硬编码为 true，会不停重建 SSH 通道重试，
     * 客户端调用永不返回（库没有开放关闭重试的公开 API，只能靠 {@link #callWithTimeout} 兜底超时）。
     * 提前探测能把这种最常见的配置错误在 1 秒内变成一条明确的错误消息，而不是让用户干等超时。
     * <p>
     * 预检只对「明确的否定结论」负责：握手失败、命令超时等一律放行，交给正式连接去报错，
     * 避免把「慢但可用」的主机误判为不可用。
     *
     * @throws IllegalStateException 远端确定不可用，消息可直接展示给用户
     */
    public void checkSshDocker(Host host) {
        if (host == null || !host.isSsh()) {
            return;
        }
        String user = StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER);
        String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
        int port = host.getSshPort() != null ? host.getSshPort() : DEFAULT_SSH_PORT;

        Session session;
        try {
            session = openSshSession(host, user, sshHost, port);
        } catch (Exception e) {
            fileLog.warn("SSH 预检未完成，交由正式连接判定: {}@{}:{} {}", user, sshHost, port, e.toString());
            return;
        }

        // 到这里只剩「明确的否定结论」会抛出，其余异常都由 exitStatus 内部消化成 null
        try {
            String prefix = additionalFileToSourcePrefix();
            Integer dockerCmd = exitStatus(session, prefix + "command -v docker");
            if (dockerCmd == null) {
                return;
            }
            if (dockerCmd != 0) {
                throw new IllegalStateException("远程主机未安装 docker（或 docker 不在 PATH 中）");
            }
            Integer dockerVersion = exitStatus(session, prefix + "docker version");
            if (dockerVersion != null && dockerVersion != 0) {
                throw new IllegalStateException("远程 docker 客户端无法访问 daemon，请检查 docker 服务与当前用户权限");
            }
        } finally {
            session.disconnect();
        }
    }

    /**
     * 带兜底超时地调用 docker，超时抛 {@link IllegalStateException}。
     * <p>
     * SSH 主机上 docker 不可用时传输层会陷入无休止重试（见 {@link #checkSshDocker}），调用永不返回。
     * 库没有超时开关，只能在外部兜底：超时后关闭客户端，其 {@code close()} 会取消在途请求并断开
     * SSH 会话，工作线程随即退出（已实测可在毫秒级打断重试）。
     */
    public <T> T callWithTimeout(Host host, Duration timeout, Function<DockerClient, T> call) {
        AtomicReference<DockerClient> holder = new AtomicReference<>();
        Future<T> future = CALL_EXECUTOR.submit(() -> {
            DockerClient client = createClient(host, null);
            holder.set(client);
            try {
                return call.apply(client);
            } finally {
                IOUtils.closeQuietly(client);
            }
        });

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            IOUtils.closeQuietly(holder.get());
            throw new IllegalStateException("测试超时（" + timeout.toSeconds() + " 秒），请检查该主机的 docker 是否可用", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private Session openSshSession(Host host, String user, String sshHost, int port) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, sshHost, port);
        // 与 buildSshClient 保持一致：内网不校验 host key，只走密码认证
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setPassword(StrUtil.nullToEmpty(host.getSshPassword()));
        // 限制认证后读写的阻塞时间，否则命令挂住时预检也会卡死
        session.setTimeout(SSH_PROBE_TIMEOUT_MILLIS);
        session.connect(SSH_PROBE_TIMEOUT_MILLIS);
        return session;
    }

    /**
     * 执行一条预检命令并返回退出码，命令输出直接丢弃。
     *
     * @return 退出码；命令异常或超时返回 {@code null}，调用方据此放弃预检
     */
    private Integer exitStatus(Session session, String command) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.connect(SSH_PROBE_TIMEOUT_MILLIS);
            long deadline = System.currentTimeMillis() + SSH_PROBE_TIMEOUT_MILLIS;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    return null;
                }
                Thread.sleep(50);
            }
            return channel.getExitStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 与传输层一致：允许用附加文件补充远端 PATH。
     */
    private String additionalFileToSourcePrefix() {
        String file = System.getenv("JSCH_DOCKER_ADDITIONAL_FILE_TO_SOURCE");
        return StrUtil.isBlank(file) ? "" : "source " + file + " && ";
    }

    private DockerClient buildTcpClient(Host host, Registry registry) {
        String dockerHost = getLocalDockerHost();
        if (host != null && StrUtil.isNotEmpty(host.getDockerHost())) {
            dockerHost = host.getDockerHost();
        }

        // 连接配置里的 https:// 前缀表示走 TLS。
        // docker-java 传输层只认 unix/npipe/tcp 三种 scheme，https 与否由「是否传 SSLConfig」决定，
        // 所以这里把 scheme 换回 tcp:// 并另外注入 TLS 配置（见 LenientSslConfig 的说明）。
        boolean tls = StrUtil.startWithIgnoreCase(dockerHost, Host.HTTPS_PREFIX);
        if (tls) {
            dockerHost = "tcp://" + StrUtil.removePrefixIgnoreCase(dockerHost, Host.HTTPS_PREFIX);
        }

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost);
        if (tls) {
            builder.withCustomSslConfig(new LenientSslConfig());
        }
        fillRegistry(builder, registry);

        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient =
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .responseTimeout(RESPONSE_TIMEOUT)
                        .build();

        // 用户配置的自定义请求头：名称为空就不发（不做任何兜底），值也原样发送
        String headerName = host == null ? null : StrUtil.trimToNull(host.getHeaderName());
        String headerValue = host == null ? null : host.getHeaderValue();
        if (headerName != null && StrUtil.isNotBlank(headerValue)) {
            httpClient = new CustomHeaderDockerHttpClient(httpClient, headerName, headerValue);
        }

        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 通过 SSH 连接远程 docker。
     * <p>
     * 登录远端后执行 {@code docker system dial-stdio}，HTTP 请求走 SSH 通道，
     * 因此远端无需暴露 2375 端口，只要装有 docker 客户端（18.09+）。
     */
    private DockerClient buildSshClient(Host host, Registry registry) {
        String user = StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER);
        int port = host.getSshPort() != null ? host.getSshPort() : DEFAULT_SSH_PORT;
        String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
        String dockerHostUri = String.format("ssh://%s@%s:%d", user, sshHost, port);

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHostUri);
        fillRegistry(builder, registry);

        DockerClientConfig config = builder.build();

        Hashtable<String, String> jschConfig = new Hashtable<>();
        // 内网环境下不校验 host key，避免首次连接因 known_hosts 缺失而失败
        jschConfig.put("StrictHostKeyChecking", "no");
        jschConfig.put("PreferredAuthentications", "password,keyboard-interactive");

        try {
            JschDockerHttpClient.Builder httpBuilder =
                    new JschDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .sslConfig(config.getSSLConfig())
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .readTimeout(RESPONSE_TIMEOUT)
                            .userInfo(new PasswordUserInfo(host.getSshPassword()))
                            .jschConfig(jschConfig);

            disableRetry(httpBuilder);

            DockerHttpClient httpClient = httpBuilder.build();

            return DockerClientImpl.getInstance(config, httpClient);
        } catch (Exception e) {
            fileLog.error("SSH 连接 docker 主机失败: {}@{}:{}", user, sshHost, port, e);
            throw new RuntimeException("SSH 连接 docker 主机失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关掉传输层硬编码的 OkHttp 重试。
     * <p>
     * 为什么必须关：SSH 主机上 docker 不可用时（没装 docker、daemon 不通），
     * {@code docker system dial-stdio} 会立即失败，而 docker-java-transport-jsch 内部的 OkHttp
     * 把 {@code retryOnConnectionFailure} 写死为 true；这个失败发生在连接建立之后，
     * 不会把 route 记为 failed，于是 OkHttp 无限重试，<b>任何</b> docker 调用都永不返回
     * （前端表现为主机详情、容器列表、测试连接一直转圈）。
     * 关掉之后同样的故障 1 秒出头就抛出真实异常。
     * <p>
     * 该库没有公开开关，只能反射调用包私有的 {@code retryOnConnectionFailure}；
     * 反射失败只记日志、不抛异常——退回库的默认行为，由
     * {@link #callWithTimeout} 之类的兜底超时保护。
     * <p>
     * 对正常主机无副作用：传输层用的是 {@code ConnectionPool(0, 1s)}，不保留空闲连接，
     * 本来就没有「复用陈旧连接需要重试」的场景。
     */
    private void disableRetry(JschDockerHttpClient.Builder builder) {
        try {
            Method method = JschDockerHttpClient.Builder.class.getDeclaredMethod("retryOnConnectionFailure", Boolean.class);
            method.setAccessible(true);
            method.invoke(builder, Boolean.FALSE);
        } catch (Exception e) {
            fileLog.warn("关闭 SSH 传输层重试失败（库版本可能变了），退回默认行为: {}", e.toString());
        }
    }

    private void fillRegistry(DefaultDockerClientConfig.Builder builder, Registry registry) {
        if (registry != null) {
            builder.withRegistryUrl(registry.getUrl())
                    .withRegistryUsername(registry.getUsername())
                    .withRegistryPassword(registry.getPassword());
        }
    }

    public DockerClient getClient(String dockerHost) {
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost);

        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient =
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .responseTimeout(RESPONSE_TIMEOUT)
                        .build();


        return DockerClientImpl.getInstance(config, httpClient);
    }

    public String getLocalDockerHost() {
        boolean windows = SystemUtil.getOsInfo().isWindows();
        return windows ? "tcp://localhost:2375" : "unix:///var/run/docker.sock";
    }


    public Map<String, String> getAppLabelFilter(String name) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app.name", name);
        return labels;
    }


    /**
     * 仅使用密码认证的 {@link UserInfo}。
     */
    private static class PasswordUserInfo implements UserInfo {
        private final String password;

        PasswordUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return password;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return true;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
            fileLog.debug("SSH: {}", message);
        }
    }

}
