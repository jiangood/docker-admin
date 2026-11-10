package io.github.jiangood.docker.admin.config;

/**
 * 注册到框架「系统管理 - 系统参数」的配置键。
 * <p>
 * 与 {@code SystemConfigService} 里的 {@code t_system_config} 不是一套：
 * 这里的键由 {@link DockerConfigProvider} 声明，值存在框架的 {@code sys_config} 表。
 * 键名沿用旧 {@code t_system_config} 的名字，方便一次性迁移。
 */
public final class DockerConfigKeys {

    /**
     * 停止容器宽限期（秒）：SIGTERM 与 SIGKILL 之间的等待时间，
     * 等价 docker compose 的 {@code stop_grace_period}。
     */
    public static final String STOP_TIMEOUT_SECONDS = "docker.container.stop-timeout-seconds";

    /** 默认 60 秒，与 docker compose 常见的 stop_grace_period 对齐。 */
    public static final int DEFAULT_STOP_TIMEOUT_SECONDS = 60;

    /** 允许配置的范围，系统参数页的输入框也按这个范围校验。 */
    public static final int MIN_STOP_TIMEOUT_SECONDS = 1;
    public static final int MAX_STOP_TIMEOUT_SECONDS = 600;

    /** 主机辅助容器的镜像地址（不含 tag）。 */
    public static final String AGENT_REPOSITORY = "docker.agent.repository";

    /** 容器日志时间段查询 / 下载的最大行数。 */
    public static final String LOG_RANGE_TAIL = "docker.logs.range-tail";
    public static final int DEFAULT_LOG_RANGE_TAIL = 10000;
    public static final int MAX_LOG_RANGE_TAIL = 1000000;

    /** 容器日志下载最多回溯的天数。 */
    public static final String LOG_DOWNLOAD_DAYS = "docker.logs.download-days";
    public static final int DEFAULT_LOG_DOWNLOAD_DAYS = 7;
    public static final int MAX_LOG_DOWNLOAD_DAYS = 3650;

    private DockerConfigKeys() {
    }
}
