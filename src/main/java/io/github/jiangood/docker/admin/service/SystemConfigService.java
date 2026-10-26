package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.docker.admin.mail.EmailSettingsInput;
import io.github.jiangood.docker.admin.mail.MailProxySettings;
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

    /**
     * 邮件发送代理：宿主机不能直连外网时，经另一台能出网的主机中转。
     * 类型为 {@code none}/{@code http}/{@code socks5}，默认 none 即直连。
     */
    public static final String EMAIL_PROXY_TYPE = "email.proxy.type";
    public static final String EMAIL_PROXY_HOST = "email.proxy.host";
    public static final String EMAIL_PROXY_PORT = "email.proxy.port";
    public static final String EMAIL_PROXY_USERNAME = "email.proxy.username";
    public static final String EMAIL_PROXY_PASSWORD = "email.proxy.password";

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

    /** 邮件发送代理；未启用时 {@link MailProxySettings#enabled()} 为 false，发送逻辑按直连处理。 */
    public MailProxySettings getEmailProxy() {
        return new MailProxySettings(
                MailProxySettings.Type.parse(getValue(EMAIL_PROXY_TYPE)),
                getEmailProxyHost(),
                getEmailProxyPort(),
                StrUtil.trimToNull(getValue(EMAIL_PROXY_USERNAME)),
                StrUtil.trimToNull(getValue(EMAIL_PROXY_PASSWORD)));
    }

    public String getEmailProxyHost() {
        return StrUtil.trimToNull(getValue(EMAIL_PROXY_HOST));
    }

    public Integer getEmailProxyPort() {
        return parseIntOrNull(getValue(EMAIL_PROXY_PORT));
    }

    /** 邮件发送设置（不含授权码与代理密码明文，只返回是否已设置）。 */
    public Map<String, Object> getEmailSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("smtpHost", getEmailSmtpHost());
        values.put("smtpPort", getEmailSmtpPort());
        values.put("username", getEmailUsername());
        values.put("fromName", getEmailFromName());
        values.put("passwordSet", StrUtil.isNotBlank(getEmailPassword()));
        // 代理地址/端口按库里的原值回显：关掉代理后再打开时不用重填
        values.put("proxyType", getEmailProxy().type().code());
        values.put("proxyHost", getEmailProxyHost());
        values.put("proxyPort", getEmailProxyPort());
        values.put("proxyUsername", StrUtil.trimToNull(getValue(EMAIL_PROXY_USERNAME)));
        values.put("proxyPasswordSet", StrUtil.isNotBlank(getValue(EMAIL_PROXY_PASSWORD)));
        return values;
    }

    /**
     * 保存邮件发送设置。授权码与代理密码留空表示沿用已保存的值，避免每次改其他项都要重填。
     */
    @Transactional
    public void saveEmailSettings(EmailSettingsInput input) {
        String host = StrUtil.blankToDefault(StrUtil.trimToNull(input.smtpHost()), DEFAULT_SMTP_HOST);
        int port = input.smtpPort() == null ? DEFAULT_SMTP_PORT : input.smtpPort();
        Assert.isTrue(port > 0 && port <= 65535, "SMTP 端口不正确");

        String user = StrUtil.trimToNull(input.username());
        if (user != null) {
            Assert.isTrue(user.contains("@"), "发件邮箱格式不正确：" + user);
        }
        String passwordToSave = StrUtil.trimToNull(input.password());

        saveValue(EMAIL_SMTP_HOST, host, "邮件 SMTP 服务器");
        saveValue(EMAIL_SMTP_PORT, String.valueOf(port), "邮件 SMTP 端口");
        saveValue(EMAIL_USERNAME, user, "发件邮箱（QQ 邮箱）");
        if (passwordToSave != null) {
            saveValue(EMAIL_PASSWORD, passwordToSave, "发件邮箱授权码");
        }
        saveValue(EMAIL_FROM_NAME, StrUtil.trimToNull(input.fromName()), "发件人名称");

        saveEmailProxy(input);
    }

    /**
     * 保存代理设置。类型为 none 时只记类型，地址等字段保留，方便以后切回来；
     * 代理密码同样留空沿用旧值。
     */
    private void saveEmailProxy(EmailSettingsInput input) {
        MailProxySettings.Type type = MailProxySettings.Type.parse(input.proxyType());
        String proxyHost = StrUtil.trimToNull(input.proxyHost());
        Integer proxyPort = input.proxyPort();
        String proxyUser = StrUtil.trimToNull(input.proxyUsername());
        String proxyPassword = StrUtil.trimToNull(input.proxyPassword());

        if (type != MailProxySettings.Type.NONE) {
            Assert.hasText(proxyHost, "请填写代理地址");
            Assert.isTrue(proxyPort != null && proxyPort > 0 && proxyPort <= 65535, "代理端口不正确");
            if (type == MailProxySettings.Type.SOCKS5) {
                // JDK 的 SOCKS 实现拿不到账号密码（只能走全局 Authenticator），这里不落库，
                // 避免切到 HTTP 代理时又冒出一组失效的凭据。
                proxyUser = null;
                proxyPassword = null;
            }
        }

        saveValue(EMAIL_PROXY_TYPE, type.code(), "邮件代理类型（none/http/socks5）");
        saveValue(EMAIL_PROXY_HOST, proxyHost, "邮件代理地址");
        saveValue(EMAIL_PROXY_PORT, proxyPort == null ? null : String.valueOf(proxyPort), "邮件代理端口");
        saveValue(EMAIL_PROXY_USERNAME, proxyUser, "邮件代理用户名");
        if (proxyPassword != null) {
            saveValue(EMAIL_PROXY_PASSWORD, proxyPassword, "邮件代理密码");
        }
    }

    private static Integer parseIntOrNull(String value) {
        String v = StrUtil.trimToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
