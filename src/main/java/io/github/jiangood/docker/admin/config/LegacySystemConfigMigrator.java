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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把旧「设置 - 全局设置」里的默认资源（{@code default.registry.id} / {@code default.build.host.id}）
 * 搬到框架的「系统管理 - 系统参数」（{@code sys_config}）。
 * <p>
 * 旧键名与新键名不同，所以显式映射；只在「新配置还没有值」时搬迁，成功后删掉旧行。
 * 迁移走 {@link SysConfigService#update}，会按动态下拉的选项校验：
 * 指向的注册中心 / 主机已被删除（选项里没有该 id）时只跳过该键并记日志，旧行保留待人工处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacySystemConfigMigrator implements ApplicationRunner {

    /** 旧 {@code t_system_config} 键 → 新 {@code sys_config} 键。 */
    private static final Map<String, String> KEY_MAPPING = new LinkedHashMap<>();

    static {
        KEY_MAPPING.put("default.registry.id", DockerConfigKeys.DEFAULT_REGISTRY_ID);
        KEY_MAPPING.put("default.build.host.id", DockerConfigKeys.DEFAULT_BUILD_HOST_ID);
    }

    private final SystemConfigRepository legacyRepository;
    private final SysConfigService sysConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("旧默认资源配置迁移失败，请到「系统管理 - 系统参数」手工确认：{}", e.toString());
        }
    }

    private void migrate() {
        List<SystemConfig> rows = legacyRepository.findAll().stream()
                .filter(config -> config.getConfigKey() != null && KEY_MAPPING.containsKey(config.getConfigKey()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }

        List<SystemConfig> handled = new ArrayList<>();
        for (SystemConfig row : rows) {
            String key = KEY_MAPPING.get(row.getConfigKey());
            // 新配置页已经填过值就说明用户改过，不覆盖
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
                log.warn("旧配置 {}={} 无法迁移到系统参数：{}", key, row.getConfigValue(), e.getMessage());
            }
        }

        if (!handled.isEmpty()) {
            legacyRepository.deleteAllInBatch(handled);
            log.info("已将 {} 条旧「全局设置」默认资源配置迁移到「系统管理 - 系统参数」", handled.size());
        }
    }
}
