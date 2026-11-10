package io.github.jiangood.docker.admin.config;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 读取「系统管理 - 参数设置」里的容器相关配置。
 * <p>
 * 停止容器的宽限期（SIGTERM → SIGKILL 之间的等待）来自「容器运行」分组：
 * docker-java 的 {@code stopContainerCmd} 默认写死 {@code t=10}（不是「不传参数」），
 * 所以必须显式 {@code withTimeout(...)} 才能改宽限期。
 * 宿主机侧的容器元数据（HostConfig.StopTimeout）docker-java 3.7.1 不支持写入，
 * 因此只覆盖平台发起的停止 / 重启。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerSettings {

    /**
     * 宽限期之外再留给 dockerd 的响应预算：SIGKILL 之后还有内核回收与网络往返，
     * 平台侧兜底超时必须比容器宽限期更宽，否则会在容器还在优雅退出时就报超时。
     */
    private static final int RESPONSE_BUDGET_SECONDS = 30;

    private final SysConfigService sysConfigService;

    /**
     * 参数设置里未填写 agent 镜像地址时使用的默认值（yml / 环境变量可覆盖）。
     */
    @Value("${docker.agent.repository:" + AgentImageUtils.DEFAULT_REPOSITORY + "}")
    private String defaultAgentRepository;

    /**
     * 生效的停止宽限期（秒）。配置缺失或读取出错时回落到默认值，避免部署直接失败。
     */
    public int graceSeconds() {
        return readInt(DockerConfigKeys.STOP_TIMEOUT_SECONDS,
                DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS,
                DockerConfigKeys.MIN_STOP_TIMEOUT_SECONDS,
                DockerConfigKeys.MAX_STOP_TIMEOUT_SECONDS);
    }

    /** 平台等待 docker 返回的兜底超时 = 宽限期 + 响应预算。 */
    public Duration budget() {
        return Duration.ofSeconds(graceSeconds() + RESPONSE_BUDGET_SECONDS);
    }

    /**
     * 实际生效的 agent 镜像地址（不含 tag）：参数设置值优先，留空时用 yml / 环境变量的默认地址。
     * <p>
     * 这里不做格式校验，与旧实现一致：真正拼镜像时由
     * {@link AgentImageUtils#resolveImage(String, String)} 校验并给出可读错误。
     */
    public String agentRepository() {
        String configured = null;
        try {
            configured = StrUtil.trimToNull(sysConfigService.getString(DockerConfigKeys.AGENT_REPOSITORY));
        } catch (RuntimeException e) {
            log.debug("读取 agent 镜像地址失败，按默认地址执行: {}", e.getMessage());
        }
        return StrUtil.blankToDefault(configured,
                AgentImageUtils.normalizeRepository(defaultAgentRepository));
    }

    /** 容器日志时间段查询 / 下载的最大行数。 */
    public int logRangeTail() {
        return readInt(DockerConfigKeys.LOG_RANGE_TAIL,
                DockerConfigKeys.DEFAULT_LOG_RANGE_TAIL, 1, DockerConfigKeys.MAX_LOG_RANGE_TAIL);
    }

    /** 容器日志下载最多回溯的天数。 */
    public int logDownloadDays() {
        return readInt(DockerConfigKeys.LOG_DOWNLOAD_DAYS,
                DockerConfigKeys.DEFAULT_LOG_DOWNLOAD_DAYS, 1, DockerConfigKeys.MAX_LOG_DOWNLOAD_DAYS);
    }

    private int readInt(String key, int defaultValue, int min, int max) {
        int value;
        try {
            value = sysConfigService.getInt(key);
        } catch (RuntimeException e) {
            log.debug("读取参数 {} 失败，按默认值 {} 执行: {}", key, defaultValue, e.getMessage());
            return defaultValue;
        }
        return value >= min && value <= max ? value : defaultValue;
    }
}
