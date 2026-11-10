package io.github.jiangood.docker.admin.config;

import io.github.jiangood.openadmin.framework.config.dynamic.ConfigSpec;
import io.github.jiangood.openadmin.framework.config.dynamic.ConfigType;
import io.github.jiangood.openadmin.modules.config.service.ConfigRegistry;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「系统参数」里的容器分组必须能通过框架的 Registry 校验，否则系统参数页会打不开；
 * 同时确认读取时的默认值、范围与 agent 镜像地址回落。
 */
class DockerSettingsTest {

    @Test
    void providerRegistersValidSpecs() {
        ConfigRegistry registry = new ConfigRegistry(List.of(new DockerConfigProvider()));

        ConfigSpec stop = registry.require(DockerConfigKeys.STOP_TIMEOUT_SECONDS);
        assertEquals(ConfigType.INT, stop.type());
        assertEquals(DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS, stop.defaultValue());
        assertEquals(1.0, stop.min());
        assertEquals(600.0, stop.max());
        assertEquals("停止容器宽限期（秒）", stop.label());

        ConfigSpec tail = registry.require(DockerConfigKeys.LOG_RANGE_TAIL);
        assertEquals(ConfigType.INT, tail.type());
        assertEquals(DockerConfigKeys.DEFAULT_LOG_RANGE_TAIL, tail.defaultValue());

        ConfigSpec days = registry.require(DockerConfigKeys.LOG_DOWNLOAD_DAYS);
        assertEquals(ConfigType.INT, days.type());
        assertEquals(DockerConfigKeys.DEFAULT_LOG_DOWNLOAD_DAYS, days.defaultValue());

        assertEquals(ConfigType.STRING, registry.require(DockerConfigKeys.AGENT_REPOSITORY).type());
    }

    @Test
    void readsConfiguredGraceSeconds() {
        DockerSettings settings = settingsReturning(DockerConfigKeys.STOP_TIMEOUT_SECONDS, 120);

        assertEquals(120, settings.graceSeconds());
        // 平台兜底超时 = 宽限期 + 30 秒响应预算
        assertEquals(150, settings.budget().toSeconds());
    }

    @Test
    void fallsBackToDefaultWhenConfigIsMissing() {
        SysConfigService configService = mock(SysConfigService.class);
        when(configService.getInt(DockerConfigKeys.STOP_TIMEOUT_SECONDS))
                .thenThrow(new IllegalStateException("配置项不存在"));

        assertEquals(DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS,
                new DockerSettings(configService).graceSeconds());
    }

    @Test
    void ignoresOutOfRangeValue() {
        // 越界值（只可能来自直接改库）按默认值执行，不静默改成边界值
        assertEquals(DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS,
                settingsReturning(DockerConfigKeys.STOP_TIMEOUT_SECONDS, 0).graceSeconds());
        assertEquals(DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS,
                settingsReturning(DockerConfigKeys.STOP_TIMEOUT_SECONDS, 9999).graceSeconds());
    }

    @Test
    void readsLogLimits() {
        DockerSettings settings = settingsReturning(DockerConfigKeys.LOG_RANGE_TAIL, 20000);
        // 未配置的项回落到默认值（mock 返回 0 → 越界）
        assertEquals(20000, settings.logRangeTail());
        assertEquals(DockerConfigKeys.DEFAULT_LOG_DOWNLOAD_DAYS, settings.logDownloadDays());
    }

    @Test
    void agentRepositoryFallsBackToYmlDefault() {
        SysConfigService configService = mock(SysConfigService.class);
        when(configService.getString(DockerConfigKeys.AGENT_REPOSITORY)).thenReturn("  ");

        // 未注入 @Value 的字段为 null，normalizeRepository 会回落到内置默认地址
        assertEquals("ghcr.io/jiangood/docker-admin-agent",
                new DockerSettings(configService).agentRepository());
    }

    private static DockerSettings settingsReturning(String key, int value) {
        SysConfigService configService = mock(SysConfigService.class);
        when(configService.getInt(key)).thenReturn(value);
        return new DockerSettings(configService);
    }
}
