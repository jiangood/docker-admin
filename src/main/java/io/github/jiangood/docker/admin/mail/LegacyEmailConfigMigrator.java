package io.github.jiangood.docker.admin.mail;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.framework.proxy.ProxySettings;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import io.github.jiangood.openadmin.modules.mail.MailConfigKeys;
import io.github.jiangood.openadmin.modules.mail.MailProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把旧版「全局设置 - 邮件发送服务」里的 {@code email.*} 配置搬到框架的「系统参数 - 邮件发送」。
 * <p>
 * 两边的键名本来就一致（{@code email.provider/username/password/from.name/proxy}），
 * 只是从本项目的 {@code t_system_config} 换到了框架的 {@code sys_config}；SMTP 服务器与端口
 * 不再单独配置，由邮箱服务商（{@link MailProvider}）决定，所以这里按旧 SMTP 主机反推服务商。
 * <p>
 * 只在「旧配置有发件邮箱」且「新配置还没有发件邮箱」时执行一次，成功后删掉旧行，
 * 避免覆盖用户在系统参数页改过的值；迁移失败只记日志，不影响启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyEmailConfigMigrator implements ApplicationRunner {

    private static final String LEGACY_SMTP_HOST = "email.smtp.host";
    private static final String LEGACY_USERNAME = "email.username";
    private static final String LEGACY_PASSWORD = "email.password";
    private static final String LEGACY_FROM_NAME = "email.from.name";
    private static final String LEGACY_PROXY_TYPE = "email.proxy.type";
    private static final String LEGACY_PROXY_HOST = "email.proxy.host";
    private static final String LEGACY_PROXY_PORT = "email.proxy.port";
    private static final String LEGACY_PROXY_USERNAME = "email.proxy.username";
    private static final String LEGACY_PROXY_PASSWORD = "email.proxy.password";

    private final SystemConfigRepository legacyRepository;
    private final SysConfigService sysConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("旧邮件配置迁移失败，请到「系统管理 - 系统参数 - 邮件发送」手工填写：{}", e.toString());
        }
    }

    private void migrate() {
        Map<String, String> legacy = legacyValues();
        String username = StrUtil.trimToNull(legacy.get(LEGACY_USERNAME));
        if (username == null) {
            return;
        }
        // 新配置页已经填过发件邮箱，说明用户已经手工配置，不覆盖
        if (sysConfigService.isOverridden(MailConfigKeys.USERNAME)) {
            return;
        }

        MailProvider provider = detectProvider(legacy, username);
        if (provider == null) {
            log.warn("无法识别旧邮件配置的邮箱类型（SMTP {}），请到「系统管理 - 系统参数 - 邮件发送」重新选择",
                    StrUtil.blankToDefault(legacy.get(LEGACY_SMTP_HOST), "未配置"));
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(MailConfigKeys.PROVIDER, provider.code());
        values.put(MailConfigKeys.USERNAME, username);
        String password = StrUtil.trimToNull(legacy.get(LEGACY_PASSWORD));
        if (password != null) {
            values.put(MailConfigKeys.PASSWORD, password);
        }
        values.put(MailConfigKeys.FROM_NAME, StrUtil.nullToEmpty(StrUtil.trimToNull(legacy.get(LEGACY_FROM_NAME))));
        // 框架的 PROXY 配置项按「一行地址」落库（update 内部会再 parse 一次），所以这里给字符串而不是 ProxySettings
        ProxySettings proxy = proxyValue(legacy);
        values.put(MailConfigKeys.PROXY, proxy.configured() ? proxy.toUrl() : "");

        sysConfigService.update(values);
        deleteLegacyRows();
        log.info("已将旧的邮件配置迁移到「系统参数 - 邮件发送」（{}）", provider.label());
    }

    private Map<String, String> legacyValues() {
        Map<String, String> values = new HashMap<>();
        for (SystemConfig config : legacyRepository.findAll()) {
            if (config.getConfigKey() != null) {
                values.put(config.getConfigKey(), config.getConfigValue());
            }
        }
        return values;
    }

    /**
     * 旧配置里存的是 SMTP 主机，先按主机反推服务商；老数据没填主机时按发件邮箱域名兜底。
     */
    private static MailProvider detectProvider(Map<String, String> legacy, String username) {
        MailProvider byHost = MailProvider.fromHost(legacy.get(LEGACY_SMTP_HOST));
        if (byHost != null) {
            return byHost;
        }
        String domain = StrUtil.subAfter(username, "@", true).toLowerCase(Locale.ROOT);
        return switch (domain) {
            case "qq.com" -> MailProvider.QQ;
            case "163.com" -> MailProvider.NET_163;
            case "126.com" -> MailProvider.NET_126;
            case "gmail.com" -> MailProvider.GMAIL;
            case "outlook.com", "hotmail.com", "live.com" -> MailProvider.OUTLOOK;
            default -> null;
        };
    }

    /** 旧配置是 type/host/port/username/password 五个键，新配置只存一行地址。 */
    private static ProxySettings proxyValue(Map<String, String> legacy) {
        ProxySettings proxy = ProxySettings.of(
                legacy.get(LEGACY_PROXY_TYPE),
                legacy.get(LEGACY_PROXY_HOST),
                parsePort(legacy.get(LEGACY_PROXY_PORT)),
                legacy.get(LEGACY_PROXY_USERNAME),
                legacy.get(LEGACY_PROXY_PASSWORD));
        return proxy.configured() ? proxy : ProxySettings.disabled();
    }

    private static Integer parsePort(String value) {
        String text = StrUtil.trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void deleteLegacyRows() {
        List<SystemConfig> rows = legacyRepository.findAll().stream()
                .filter(config -> config.getConfigKey() != null && config.getConfigKey().startsWith("email."))
                .toList();
        if (!rows.isEmpty()) {
            legacyRepository.deleteAllInBatch(rows);
        }
    }
}
