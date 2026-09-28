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
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DockerClientManager {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3600);

    private static final int DEFAULT_SSH_PORT = 22;
    private static final String DEFAULT_SSH_USER = "root";

    /**
     * SSH 客户端的缓存，避免每次操作都重新建立 SSH 连接。
     * key 由主机 id 与相关配置计算得到，配置变化时自动失效。
     */
    private final Map<String, DockerClient> sshClientCache = new ConcurrentHashMap<>();
    /**
     * hostKey -> 当前缓存 key，用于配置变化时清理旧连接。
     */
    private final Map<String, String> sshClientKeyByHost = new ConcurrentHashMap<>();
    private final Object sshClientLock = new Object();


    public DockerClient getClient(Host host) {
        return this.getClient(host, new Registry());
    }


    public DockerClient getClient(Host host, Registry registry) {
        if (host != null && host.isSsh()) {
            return getCachedSshClient(host, registry);
        }
        return createClient(host, registry);
    }

    /**
     * 每次新建连接，用于测试连接等一次性场景。
     */
    public DockerClient createClient(Host host, Registry registry) {
        if (host != null && host.isSsh()) {
            return buildSshClient(host, registry);
        }
        return buildTcpClient(host, registry);
    }

    /**
     * 主机配置发生变化（保存/删除）后调用，关闭并移除缓存的 SSH 连接。
     */
    public void invalidate(String hostId) {
        if (StrUtil.isBlank(hostId)) {
            return;
        }
        closeSshClient(hostId);
    }

    private DockerClient getCachedSshClient(Host host, Registry registry) {
        String hostKey = sshHostKey(host);
        String cacheKey = sshCacheKey(host, registry);

        // 配置变化：关闭旧的 SSH 连接
        String previousKey = sshClientKeyByHost.get(hostKey);
        if (previousKey != null && !previousKey.equals(cacheKey)) {
            closeSshClient(hostKey);
        }

        DockerClient cached = sshClientCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        synchronized (sshClientLock) {
            cached = sshClientCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            DockerClient client = buildSshClient(host, registry);
            sshClientCache.put(cacheKey, client);
            sshClientKeyByHost.put(hostKey, cacheKey);
            return client;
        }
    }

    private void closeSshClient(String hostKey) {
        String cacheKey = sshClientKeyByHost.remove(hostKey);
        if (cacheKey != null) {
            IOUtils.closeQuietly(sshClientCache.remove(cacheKey));
        }
    }

    private String sshHostKey(Host host) {
        if (StrUtil.isNotBlank(host.getId())) {
            return host.getId();
        }
        String user = StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER);
        return user + "@" + host.getSshHost() + ":" + host.getSshPort();
    }

    private String sshCacheKey(Host host, Registry registry) {
        StringBuilder sb = new StringBuilder(sshHostKey(host));
        sb.append('|').append(StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost()))
                .append('|').append(host.getSshPort())
                .append('|').append(StrUtil.blankToDefault(host.getSshUser(), DEFAULT_SSH_USER))
                .append('|').append(host.getSshPassword());
        if (registry != null) {
            sb.append('|').append(registry.getId())
                    .append('|').append(registry.getUpdateTime())
                    .append('|').append(registry.getUrl())
                    .append('|').append(registry.getUsername())
                    .append('|').append(registry.getPassword());
        }
        return sb.toString();
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
