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
