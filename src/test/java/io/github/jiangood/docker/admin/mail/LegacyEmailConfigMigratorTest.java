package io.github.jiangood.docker.admin.mail;

import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import io.github.jiangood.openadmin.modules.mail.MailConfigKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 旧 `t_system_config` 的 email.* 搬到框架 sys_config 的映射规则（不需要 Spring 上下文）。
 */
class LegacyEmailConfigMigratorTest {

    @Test
    void movesLegacyConfigToFrameworkKeys() {
        SystemConfigRepository repository = repositoryRows(
                row("email.smtp.host", "smtp.qq.com"),
                row("email.username", "sender@qq.com"),
                row("email.password", "auth-code"),
                row("email.from.name", "构建机器人"),
                row("email.proxy.type", "http"),
                row("email.proxy.host", "192.168.1.10"),
                row("email.proxy.port", "8080"),
                row("email.proxy.username", "proxy-user"),
                row("email.proxy.password", "proxy-pass"));
        SysConfigService configService = mock(SysConfigService.class);
        doReturn(false).when(configService).isOverridden(MailConfigKeys.USERNAME);

        new LegacyEmailConfigMigrator(repository, configService).run(null);

        ArgumentCaptor<Map<String, Object>> captor = captor();
        verify(configService).update(captor.capture());
        Map<String, Object> values = captor.getValue();
        assertEquals("qq", values.get(MailConfigKeys.PROVIDER));
        assertEquals("sender@qq.com", values.get(MailConfigKeys.USERNAME));
        assertEquals("auth-code", values.get(MailConfigKeys.PASSWORD));
        assertEquals("构建机器人", values.get(MailConfigKeys.FROM_NAME));
        // 框架的 PROXY 项按一行地址落库，保存时再解析回 ProxySettings
        assertEquals("http://proxy-user:proxy-pass@192.168.1.10:8080", values.get(MailConfigKeys.PROXY));
        // 迁移成功后删掉旧行，避免下次启动重复迁移
        verify(repository).deleteAllInBatch(anyList());
    }

    @Test
    void detectsProviderFromUsernameWhenHostMissing() {
        SystemConfigRepository repository = repositoryRows(row("email.username", "sender@163.com"));
        SysConfigService configService = mock(SysConfigService.class);
        doReturn(false).when(configService).isOverridden(MailConfigKeys.USERNAME);

        new LegacyEmailConfigMigrator(repository, configService).run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configService).update(captor.capture());
        assertEquals("163", captor.getValue().get(MailConfigKeys.PROVIDER));
        // 没有代理配置时写入空串（不使用代理）
        assertEquals("", captor.getValue().get(MailConfigKeys.PROXY));
    }

    @Test
    void skipsWhenFrameworkConfigAlreadyHasUsername() {
        SystemConfigRepository repository = repositoryRows(row("email.username", "sender@qq.com"));
        SysConfigService configService = mock(SysConfigService.class);
        doReturn(true).when(configService).isOverridden(MailConfigKeys.USERNAME);

        new LegacyEmailConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void skipsWhenNothingWasConfigured() {
        SystemConfigRepository repository = repositoryRows(row("default.registry.id", "1"));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacyEmailConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void skipsWhenProviderCannotBeDetected() {
        SystemConfigRepository repository = repositoryRows(
                row("email.username", "sender@unknown.local"),
                row("email.smtp.host", "smtp.unknown.local"));
        SysConfigService configService = mock(SysConfigService.class);
        doReturn(false).when(configService).isOverridden(MailConfigKeys.USERNAME);

        new LegacyEmailConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        // 识别不了服务商时保留旧行，用户可在系统参数里手工选
        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static SystemConfigRepository repositoryRows(SystemConfig... rows) {
        SystemConfigRepository repository = mock(SystemConfigRepository.class);
        doReturn(List.of(rows)).when(repository).findAll();
        return repository;
    }

    private static SystemConfig row(String key, String value) {
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
