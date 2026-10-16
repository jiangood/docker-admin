package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.framework.data.BaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置读写。这里只负责配置键值，不依赖业务 Service，避免形成循环依赖。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService extends BaseService<SystemConfig> {

    public static final String DEFAULT_REGISTRY_ID = "default.registry.id";
    public static final String DEFAULT_BUILD_HOST_ID = "default.build.host.id";

    private final SystemConfigRepository repository;
    private final RegistryRepository registryRepository;
    private final HostRepository hostRepository;

    public String getValue(String key) {
        return repository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(null);
    }

    public String getDefaultRegistryId() {
        return getValue(DEFAULT_REGISTRY_ID);
    }

    public String getDefaultBuildHostId() {
        return getValue(DEFAULT_BUILD_HOST_ID);
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
