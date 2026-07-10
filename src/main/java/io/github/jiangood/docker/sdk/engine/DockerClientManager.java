package io.github.jiangood.docker.sdk.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.config.Registry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class DockerClientManager {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);


    public DockerClient getClient(Host host) {
        return this.getClient(host, new Registry());
    }


    public DockerClient getClient(Host host, Registry registry) {
        String dockerHost = getLocalDockerHost();
        if (host != null && StrUtil.isNotEmpty(host.getDockerHost())) {
            dockerHost = host.getDockerHost();
        }


        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost);
        if (registry != null) {
            builder.withRegistryUrl(registry.getUrl())
                    .withRegistryUsername(registry.getUsername())
                    .withRegistryPassword(registry.getPassword());
        }


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


}
