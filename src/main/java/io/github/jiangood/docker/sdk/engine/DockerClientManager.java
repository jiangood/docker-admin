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
import com.jcraft.jsch.UserInfo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

@Service
@Slf4j
public class DockerClientManager {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3600);

    private static final int DEFAULT_SSH_PORT = 22;
    private static final String DEFAULT_SSH_USER = "root";


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

    private DockerClient buildTcpClient(Host host, Registry registry) {
        String dockerHost = getLocalDockerHost();
        if (host != null && StrUtil.isNotEmpty(host.getDockerHost())) {
            dockerHost = host.getDockerHost();
        }

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost);
        fillRegistry(builder, registry);

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
            DockerHttpClient httpClient =
                    new JschDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .sslConfig(config.getSSLConfig())
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .readTimeout(RESPONSE_TIMEOUT)
                            .userInfo(new PasswordUserInfo(host.getSshPassword()))
                            .jschConfig(jschConfig)
                            .build();

            return DockerClientImpl.getInstance(config, httpClient);
        } catch (Exception e) {
            log.error("SSH 连接 docker 主机失败: {}@{}:{}", user, sshHost, port, e);
            throw new RuntimeException("SSH 连接 docker 主机失败: " + e.getMessage(), e);
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
            log.debug("SSH: {}", message);
        }
    }

}
