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

    /** 全局邮件发送服务（目前为 QQ 邮箱 SMTP） */
    public static final String EMAIL_SMTP_HOST = "email.smtp.host";
    public static final String EMAIL_SMTP_PORT = "email.smtp.port";
    public static final String EMAIL_USERNAME = "email.username";
    public static final String EMAIL_PASSWORD = "email.password";
    public static final String EMAIL_FROM_NAME = "email.from.name";

    /** 目前只支持 QQ 邮箱，SMTP 默认值即 QQ 邮箱参数 */
    public static final String DEFAULT_SMTP_HOST = "smtp.qq.com";
    public static final int DEFAULT_SMTP_PORT = 465;

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

    public String getEmailSmtpHost() {
        return StrUtil.blankToDefault(StrUtil.trimToNull(getValue(EMAIL_SMTP_HOST)), DEFAULT_SMTP_HOST);
    }

    public int getEmailSmtpPort() {
        String value = StrUtil.trimToNull(getValue(EMAIL_SMTP_PORT));
        if (value == null) {
            return DEFAULT_SMTP_PORT;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return DEFAULT_SMTP_PORT;
        }
    }

    public String getEmailUsername() {
        return StrUtil.trimToNull(getValue(EMAIL_USERNAME));
    }

    public String getEmailPassword() {
        return StrUtil.trimToNull(getValue(EMAIL_PASSWORD));
    }

    public String getEmailFromName() {
        return StrUtil.trimToNull(getValue(EMAIL_FROM_NAME));
    }

    /** 邮件发送设置（不含授权码明文，只返回是否已设置）。 */
    public Map<String, Object> getEmailSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("smtpHost", getEmailSmtpHost());
        values.put("smtpPort", getEmailSmtpPort());
        values.put("username", getEmailUsername());
        values.put("fromName", getEmailFromName());
        values.put("passwordSet", StrUtil.isNotBlank(getEmailPassword()));
        return values;
    }

    /**
     * 保存邮件发送设置。授权码留空表示沿用已保存的值，避免每次改其他项都要重填。
     */
    @Transactional
    public void saveEmailSettings(String smtpHost, Integer smtpPort, String username,
                                  String password, String fromName) {
        String host = StrUtil.blankToDefault(StrUtil.trimToNull(smtpHost), DEFAULT_SMTP_HOST);
        int port = smtpPort == null ? DEFAULT_SMTP_PORT : smtpPort;
        Assert.isTrue(port > 0 && port <= 65535, "SMTP 端口不正确");

        String user = StrUtil.trimToNull(username);
        if (user != null) {
            Assert.isTrue(user.contains("@"), "发件邮箱格式不正确：" + user);
        }
        String passwordToSave = StrUtil.trimToNull(password);

        saveValue(EMAIL_SMTP_HOST, host, "邮件 SMTP 服务器");
        saveValue(EMAIL_SMTP_PORT, String.valueOf(port), "邮件 SMTP 端口");
        saveValue(EMAIL_USERNAME, user, "发件邮箱（QQ 邮箱）");
        if (passwordToSave != null) {
            saveValue(EMAIL_PASSWORD, passwordToSave, "发件邮箱授权码");
        }
        saveValue(EMAIL_FROM_NAME, StrUtil.trimToNull(fromName), "发件人名称");
    }
}
