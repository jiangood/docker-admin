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
 * 旧 `t_system_config` 的 docker.* 搬到框架 sys_config：不能覆盖用户已经在新页面改过的值，
 * 迁移成功后旧行要删掉，否则每次启动都会重复迁移。
 */
class LegacyDockerConfigMigratorTest {

    @Test
    void movesThreeLegacyKeysAndDeletesRows() {
        SystemConfigRepository repository = repositoryRows(
                row(DockerConfigKeys.AGENT_REPOSITORY, "registry.local/docker-admin-agent"),
                row(DockerConfigKeys.LOG_RANGE_TAIL, "20000"),
                row(DockerConfigKeys.LOG_DOWNLOAD_DAYS, "30"));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacyDockerConfigMigrator(repository, configService).run(null);

        verify(configService, times(3)).update(anyMap());
        verify(repository).deleteAllInBatch(anyList());
    }

    @Test
    void doesNotOverwriteValueAlreadySetInParameterPage() {
        SystemConfigRepository repository = repositoryRows(
                row(DockerConfigKeys.LOG_RANGE_TAIL, "20000"));
        SysConfigService configService = mock(SysConfigService.class);
        when(configService.isOverridden(DockerConfigKeys.LOG_RANGE_TAIL)).thenReturn(true);

        new LegacyDockerConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        // 用户已经配置过，旧行直接清理
        verify(repository).deleteAllInBatch(anyList());
    }

    @Test
    void skipsInvalidLegacyValueAndKeepsRow() {
        SystemConfigRepository repository = repositoryRows(
                row(DockerConfigKeys.LOG_RANGE_TAIL, "999999999"));
        SysConfigService configService = mock(SysConfigService.class);
        // 系统参数页会校验范围，旧库里的越界值按异常处理
        when(configService.update(Map.of(DockerConfigKeys.LOG_RANGE_TAIL, "999999999")))
                .thenThrow(new IllegalArgumentException("超出范围"));

        new LegacyDockerConfigMigrator(repository, configService).run(null);

        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void skipsWhenNothingWasConfigured() {
        SystemConfigRepository repository = repositoryRows(row("default.registry.id", "1"));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacyDockerConfigMigrator(repository, configService).run(null);

        verify(configService, never()).update(anyMap());
        verify(repository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void cleansUpBlankLegacyRowWithoutWriting() {
        SystemConfigRepository repository = repositoryRows(
                row(DockerConfigKeys.AGENT_REPOSITORY, "  "));
        SysConfigService configService = mock(SysConfigService.class);

        new LegacyDockerConfigMigrator(repository, configService).run(null);

        // 空值等于没配置，不写新配置，但旧行仍要清掉避免每次启动重复处理
        verify(configService, never()).update(anyMap());
        verify(repository).deleteAllInBatch(anyList());
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
