package io.github.jiangood.docker.admin.config;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.SystemConfigRepository;
import io.github.jiangood.docker.admin.entity.SystemConfig;
import io.github.jiangood.openadmin.modules.config.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把旧「设置 - 全局设置」里的 {@code docker.agent.repository}、
 * {@code docker.logs.range-tail}、{@code docker.logs.download-days}
 * 搬到框架的「系统管理 - 参数设置」（{@code sys_config}）。
 * <p>
 * 两边键名一致，只是换了表；只在「新配置还没有值」时搬迁，成功后删掉旧行。
 * 某个值超出新配置的范围时只跳过该键并记日志，不影响其他键与启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyDockerConfigMigrator implements ApplicationRunner {

    private static final Set<String> LEGACY_KEYS = new LinkedHashSet<>(List.of(
            DockerConfigKeys.AGENT_REPOSITORY,
            DockerConfigKeys.LOG_RANGE_TAIL,
            DockerConfigKeys.LOG_DOWNLOAD_DAYS));

    private final SystemConfigRepository legacyRepository;
    private final SysConfigService sysConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("旧容器配置迁移失败，请到「系统管理 - 参数设置」手工确认：{}", e.toString());
        }
    }

    private void migrate() {
        List<SystemConfig> rows = legacyRepository.findAll().stream()
                .filter(config -> config.getConfigKey() != null && LEGACY_KEYS.contains(config.getConfigKey()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }

        List<SystemConfig> handled = new ArrayList<>();
        for (SystemConfig row : rows) {
            String key = row.getConfigKey();
            // 新配置页已经填过值（哪怕是空字符串之外的任何值）就说明用户改过，不覆盖
            if (sysConfigService.isOverridden(key)) {
                handled.add(row);
                continue;
            }
            if (StrUtil.isBlank(row.getConfigValue())) {
                handled.add(row);
                continue;
            }
            try {
                sysConfigService.update(Map.of(key, row.getConfigValue()));
                handled.add(row);
            } catch (RuntimeException e) {
                log.warn("旧配置 {}={} 无法迁移到参数设置：{}", key, row.getConfigValue(), e.getMessage());
            }
        }

        if (!handled.isEmpty()) {
            legacyRepository.deleteAllInBatch(handled);
            log.info("已将 {} 条旧「全局设置」容器配置迁移到「系统管理 - 参数设置」", handled.size());
        }
    }
}
