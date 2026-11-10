package io.github.jiangood.docker.admin.config;

/**
 * 注册到框架「系统管理 - 系统参数」的配置键。
 * <p>
 * 这里的键由 {@link DockerConfigProvider} 声明，值存在框架的 {@code sys_config} 表；
 * 旧的 {@code t_system_config} 只由一次性迁移器读取，不再有新写入。
 * 容器相关的键名沿用旧 {@code t_system_config} 的名字，方便一次性迁移。
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

    /** 默认镜像注册中心（{@code t_registry.id}），留空表示不设置默认值。 */
    public static final String DEFAULT_REGISTRY_ID = "docker.default.registry-id";

    /** 默认构建主机（{@code t_host.id}），留空表示不设置默认值。 */
    public static final String DEFAULT_BUILD_HOST_ID = "docker.default.build-host-id";

    /**
     * 主机终端会话：断开后「多久没有输出 / 输入」才回收（分钟）。
     * <p>
     * 注意计时基准是最后一次输出或输入，而不是断开时刻：长耗时任务（例如 docker pull
     * 十几 G 的镜像）持续刷进度时会一直保活。
     */
    public static final String CLI_SESSION_IDLE_MINUTES = "docker.cli.session-idle-minutes";
    public static final int DEFAULT_CLI_SESSION_IDLE_MINUTES = 30;
    public static final int MIN_CLI_SESSION_IDLE_MINUTES = 1;
    public static final int MAX_CLI_SESSION_IDLE_MINUTES = 1440;

    /** 主机终端会话：不管有没有输出，断开超过多少小时强制回收，防止僵尸会话无限占用。 */
    public static final String CLI_SESSION_MAX_DETACHED_HOURS = "docker.cli.session-max-detached-hours";
    public static final int DEFAULT_CLI_SESSION_MAX_DETACHED_HOURS = 24;
    public static final int MIN_CLI_SESSION_MAX_DETACHED_HOURS = 1;
    public static final int MAX_CLI_SESSION_MAX_DETACHED_HOURS = 168;

    private DockerConfigKeys() {
    }
}
