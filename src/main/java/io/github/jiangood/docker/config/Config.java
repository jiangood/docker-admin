package io.github.jiangood.docker.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * yml 中的框架级配置（cfg.*）。注册中心、代码源等业务配置一律在后台维护，不走配置文件。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cfg")
public class Config {

    /**
     * Compose 管理使用的辅助镜像：自带 docker CLI 与 compose 插件。
     * <p>
     * 平台与主机之间只有 docker API（没有 shell），compose 命令由这个镜像起的临时容器代跑。
     * 内网环境可改成自己的镜像地址（如 {@code registry.local/docker:cli}）。
     */
    private String composeHelperImage = "docker:cli";

    /**
     * 单条 compose 命令的最长执行时间（秒），超时后强制结束临时容器。
     * {@code up -d} 首次拉镜像可能较久，默认 30 分钟。
     */
    private int composeTimeoutSeconds = 1800;

    /**
     * 本机 Docker（unix socket）模式下，挂进辅助容器的 socket 路径。
     * TCP 主机不使用该配置，而是直接让辅助容器以 host 网络访问 dockerd。
     */
    private String composeSocketPath = "/var/run/docker.sock";

}
