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
     * 界面只填一行地址（{@code http://user:pass@192.168.1.10:8080}），
     * 解析后仍按 type/host/port/username/password 五个键落库，旧数据无需迁移。
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
        // 代理只回显一行地址；已保存的密码显示为 ***，不再单独回传
        values.put("proxyUrl", getEmailProxy().displayUrl());
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
     * 保存代理设置：界面只填一行地址，这里解析后仍按 type/host/port/username/password 落库。
     * 留空表示不使用代理，同时清掉已保存的地址与凭据；
     * 密码写 {@code ***} 或省略表示沿用已保存的密码。
     */
    private void saveEmailProxy(EmailSettingsInput input) {
        String raw = StrUtil.trimToNull(input.proxyUrl());
        if (raw == null) {
            saveValue(EMAIL_PROXY_TYPE, MailProxySettings.Type.NONE.code(), "邮件代理类型（none/http/socks5）");
            saveValue(EMAIL_PROXY_HOST, null, "邮件代理地址");
            saveValue(EMAIL_PROXY_PORT, null, "邮件代理端口");
            saveValue(EMAIL_PROXY_USERNAME, null, "邮件代理用户名");
            saveValue(EMAIL_PROXY_PASSWORD, null, "邮件代理密码");
            return;
        }

        MailProxySettings parsed = MailProxySettings.parse(raw);
        String proxyUser = parsed.username();
        String proxyPassword = parsed.password();
        if (proxyUser == null) {
            // 没写用户名就没有认证
            proxyPassword = null;
        } else if (proxyPassword == null || MailProxySettings.PASSWORD_MASK.equals(proxyPassword)) {
            // 回显的 *** 只是占位；没填新密码就沿用已存的
            proxyPassword = StrUtil.trimToNull(getValue(EMAIL_PROXY_PASSWORD));
        }

        saveValue(EMAIL_PROXY_TYPE, parsed.type().code(), "邮件代理类型（none/http/socks5）");
        saveValue(EMAIL_PROXY_HOST, parsed.host(), "邮件代理地址");
        saveValue(EMAIL_PROXY_PORT, String.valueOf(parsed.port()), "邮件代理端口");
        saveValue(EMAIL_PROXY_USERNAME, proxyUser, "邮件代理用户名");
        saveValue(EMAIL_PROXY_PASSWORD, proxyPassword, "邮件代理密码");
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
