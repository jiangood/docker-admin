package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置读写。这里只负责配置键值，不依赖业务 Service，避免形成循环依赖。
 * <p>
 * 邮件发件账号不在这里：升级到 open-admin 3.1.12 后由框架的「参数设置 - 邮件发送」维护
 * （{@code sys_config} 表的 {@code email.*}），旧 {@code t_system_config} 里的值由
 * {@code LegacyEmailConfigMigrator} 一次性搬迁。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService extends BaseService<SystemConfig> {

    public static final String DEFAULT_REGISTRY_ID = "default.registry.id";
    public static final String DEFAULT_BUILD_HOST_ID = "default.build.host.id";

    /**
     * 主机辅助容器镜像地址（不含 tag）。tag 在每台主机部署时单独选择。
     */
    public static final String AGENT_REPOSITORY = "docker.agent.repository";

    /**
     * 容器日志：时间段查询 / 下载的最大行数（默认 10000）。
     */
    public static final String LOG_RANGE_TAIL = "docker.logs.range-tail";

    /**
     * 容器日志下载最多回溯的天数（默认 7 天）。
     */
    public static final String LOG_DOWNLOAD_DAYS = "docker.logs.download-days";
    public static final int DEFAULT_LOG_RANGE_TAIL = 10000;
    public static final int DEFAULT_LOG_DOWNLOAD_DAYS = 7;

    private final SystemConfigRepository repository;
    private final RegistryRepository registryRepository;
    private final HostRepository hostRepository;

    /**
     * 全局设置未配置时使用的 agent 镜像地址（yml / 环境变量可覆盖）。
     */
    @Value("${docker.agent.repository:ghcr.io/jiangood/docker-admin-agent}")
    private String defaultAgentRepository;

    public String getValue(String key) {
        return repository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(null);
    }

    public String getDefaultRegistryId() {
        return getValue(DEFAULT_REGISTRY_ID);
    }

    public String getDefaultBuildHostId() {
        return getValue(DEFAULT_BUILD_HOST_ID);
    }

    /**
     * 实际生效的 agent 镜像地址（不含 tag）：数据库值优先，空则用 yml / 环境变量。
     */
    public String getAgentRepository() {
        return StrUtil.blankToDefault(
                StrUtil.trimToNull(getValue(AGENT_REPOSITORY)),
                AgentImageUtils.normalizeRepository(defaultAgentRepository));
    }

    public Map<String, Object> getAgentSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("repository", getAgentRepository());
        values.put("defaultRepository", AgentImageUtils.normalizeRepository(defaultAgentRepository));
        return values;
    }

    /**
     * 保存 agent 镜像地址；空值表示清除数据库配置，回落到 yml / 环境变量的默认地址。
     */
    @Transactional
    public void saveAgentSettings(String repository) {
        String value = StrUtil.trimToNull(repository);
        if (value != null) {
            value = AgentImageUtils.normalizeRepository(value);
        }
        saveValue(AGENT_REPOSITORY, value, "主机辅助容器镜像地址（不含 tag）");
    }

    public Map<String, String> getDefaults() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("defaultRegistryId", getDefaultRegistryId());
        values.put("defaultBuildHostId", getDefaultBuildHostId());
        return values;
    }

    // ------------------------------------------------------------------ 容器日志

    public int getLogRangeTail() {
        return readPositiveInt(LOG_RANGE_TAIL, DEFAULT_LOG_RANGE_TAIL);
    }

    public int getLogDownloadDays() {
        return readPositiveInt(LOG_DOWNLOAD_DAYS, DEFAULT_LOG_DOWNLOAD_DAYS);
    }

    public Map<String, Object> getLogSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rangeTail", getLogRangeTail());
        values.put("downloadDays", getLogDownloadDays());
        values.put("defaultRangeTail", DEFAULT_LOG_RANGE_TAIL);
        values.put("defaultDownloadDays", DEFAULT_LOG_DOWNLOAD_DAYS);
        return values;
    }

    @Transactional
    public void saveLogSettings(Integer rangeTail, Integer downloadDays) {
        int tail = rangeTail == null ? DEFAULT_LOG_RANGE_TAIL : rangeTail;
        int days = downloadDays == null ? DEFAULT_LOG_DOWNLOAD_DAYS : downloadDays;
        Assert.isTrue(tail >= 1 && tail <= 1000000, "日志最大行数需在 1 ~ 1000000 之间");
        Assert.isTrue(days >= 1 && days <= 3650, "日志下载天数需在 1 ~ 3650 之间");
        saveValue(LOG_RANGE_TAIL, String.valueOf(tail), "容器日志时间段查询 / 下载的最大行数");
        saveValue(LOG_DOWNLOAD_DAYS, String.valueOf(days), "容器日志下载最多回溯的天数");
    }

    private int readPositiveInt(String key, int defaultValue) {
        String value = StrUtil.trimToNull(getValue(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public Map<String, String> saveDefaults(String registryId, String hostId) {
        if (StrUtil.isNotBlank(registryId)) {
            Assert.isTrue(registryRepository.existsById(registryId), "所选注册中心不存在");
        }
        if (StrUtil.isNotBlank(hostId)) {
            Assert.isTrue(hostRepository.existsById(hostId), "所选构建主机不存在");
        }
        saveValue(DEFAULT_REGISTRY_ID, registryId, "默认镜像注册中心");
        saveValue(DEFAULT_BUILD_HOST_ID, hostId, "默认构建主机");
        return getDefaults();
    }

    @Transactional
    public void saveValue(String key, String value, String description) {
        SystemConfig config = repository.findByConfigKey(key).orElseGet(SystemConfig::new);
        config.setConfigKey(key);
        config.setConfigValue(StrUtil.trimToNull(value));
        config.setDescription(description);
        if (StrUtil.isBlank(config.getId())) {
            create(config);
        } else {
            repository.save(config);
        }
    }
}
