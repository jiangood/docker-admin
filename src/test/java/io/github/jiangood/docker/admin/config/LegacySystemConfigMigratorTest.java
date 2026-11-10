package io.github.jiangood.docker.admin.config;

import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧 `t_system_config` 的默认资源（default.registry.id / default.build.host.id）
 * 搬到框架 sys_config 的新键：不能覆盖用户已经在新页面改过的值，
 * 迁移成功后旧行要删掉，否则每次启动都会重复迁移。
 */
class LegacySystemConfigMigratorTest {

    @Test
    void movesLegacyKeysToNewKeysAndDeletesRows() {
        SystemConfigRepository repository = repositoryRows(
                row("default.registry.id", "r1"),
                row("default.build.host.id", "h1"));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacySystemConfigMigrator(repository, configService).run(null);

        verify(configService).update(Map.of(DockerConfigKeys.DEFAULT_REGISTRY_ID, "r1"));
        verify(configService).update(Map.of(DockerConfigKeys.DEFAULT_BUILD_HOST_ID, "h1"));
        verify(repository).deleteAllInBatch(anyList());
    }

    @Test
    void doesNotOverwriteValueAlreadySetInParameterPage() {
        SystemConfigRepository repository = repositoryRows(
                row("default.registry.id", "r1"));
        SysConfigService configService = mock(SysConfigService.class);
        when(configService.isOverridden(DockerConfigKeys.DEFAULT_REGISTRY_ID)).thenReturn(true);

        new LegacySystemConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        // 用户已经配置过，旧行直接清理
        verify(repository).deleteAllInBatch(anyList());
    }

    @Test
    void skipsValueMissingFromOptionsAndKeepsRow() {
        SystemConfigRepository repository = repositoryRows(
                row("default.build.host.id", "deleted-host"));
        SysConfigService configService = mock(SysConfigService.class);
        // 主机已被删除时动态下拉里没有该选项，update 会抛异常
        when(configService.update(Map.of(DockerConfigKeys.DEFAULT_BUILD_HOST_ID, "deleted-host")))
                .thenThrow(new IllegalArgumentException("选项不存在"));

        new LegacySystemConfigMigrator(repository, configService).run(null);

        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void ignoresUnrelatedLegacyKeys() {
        SystemConfigRepository repository = repositoryRows(
                row(DockerConfigKeys.AGENT_REPOSITORY, "registry.local/agent"));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacySystemConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void cleansUpBlankLegacyRowWithoutWriting() {
        SystemConfigRepository repository = repositoryRows(
                row("default.registry.id", "  "));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacySystemConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        verify(repository, times(1)).deleteAllInBatch(anyList());
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
