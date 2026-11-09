package io.github.jiangood.docker.admin;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.HostAgentConfigRepository;
import io.github.jiangood.docker.admin.entity.HostAgentConfig;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 一次性数据搬迁：主机上的 Agent 配置从 {@code t_host.file_root / agent_image_tag}
 * 搬到工具菜单的 {@code t_host_agent_config}。
 * <p>
 * 为什么要这个类：Agent 配置已经独立成实体（一主机一条），主机表上的这两个字段不再使用；
 * 但 Hibernate 的 {@code ddl-auto=update} 不会删列，升级后老数据会留在旧列里静默失效，
 * 表现为「升级后 Agent 页面看不到以前部署过的主机」。这里在启动后把旧值补成 Agent 配置。
 * <p>
 * 幂等：已有配置的主机直接跳过；搬完把旧列清空，避免用户删掉配置后重启又被旧数据重建。
 * 全新库没有旧列，直接跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostAgentConfigMigration {

    private final JdbcTemplate jdbcTemplate;
    private final HostAgentConfigRepository configRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        boolean hasRoot = hasColumn("file_root");
        boolean hasTag = hasColumn("agent_image_tag");
        if (!hasRoot && !hasTag) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(buildSelect(hasRoot, hasTag));
            int migrated = 0;
            for (Map<String, Object> row : rows) {
                String hostId = text(row, "id");
                if (StrUtil.isBlank(hostId)) {
                    continue;
                }
                boolean exists = configRepository.exists(
                        Spec.<HostAgentConfig>of().eq(HostAgentConfig.Fields.hostId, hostId));
                if (exists) {
                    continue;
                }
                HostAgentConfig config = new HostAgentConfig();
                config.setHostId(hostId);
                config.setName(StrUtil.blankToDefault(text(row, "name"), "主机 Agent"));
                config.setImageTag(normalizeTag(text(row, "agent_image_tag")));
                config.setFileRoot(normalizeRoot(text(row, "file_root")));
                config.setRemark("由旧版主机配置迁移");
                configRepository.save(config);
                migrated++;
            }
            if (migrated > 0) {
                log.info("主机 Agent 配置已从 t_host 迁移到 t_host_agent_config，共 {} 台主机", migrated);
            }
            // 迁移完清掉旧列：配置删掉后重启不会又冒出来；数据已经在新表里
            clearLegacyColumns(hasRoot, hasTag);
        } catch (Exception e) {
            // 老列不存在、数据库不支持等：没有东西可搬，不影响启动
            log.warn("主机 Agent 配置迁移失败，本次跳过: {}", e.toString());
        }
    }

    private static String buildSelect(boolean hasRoot, boolean hasTag) {
        StringBuilder sql = new StringBuilder("select id, name");
        if (hasRoot) {
            sql.append(", file_root");
        }
        if (hasTag) {
            sql.append(", agent_image_tag");
        }
        sql.append(" from t_host where ");
        if (hasRoot && hasTag) {
            sql.append("(file_root is not null and file_root <> '')")
                    .append(" or (agent_image_tag is not null and agent_image_tag <> '')");
        } else if (hasRoot) {
            sql.append("file_root is not null and file_root <> ''");
        } else {
            sql.append("agent_image_tag is not null and agent_image_tag <> ''");
        }
        return sql.toString();
    }

    private void clearLegacyColumns(boolean hasRoot, boolean hasTag) {
        StringBuilder sql = new StringBuilder("update t_host set ");
        if (hasRoot) {
            sql.append("file_root = null");
        }
        if (hasTag) {
            if (hasRoot) {
                sql.append(", ");
            }
            sql.append("agent_image_tag = null");
        }
        jdbcTemplate.update(sql.toString());
    }

    private static String normalizeRoot(String root) {
        try {
            String normalized = HostRootPolicy.normalizeRoot(root);
            HostRootPolicy.assertValidRoot(normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("旧版 Agent 目录「{}」不可用，迁移时回落到默认目录: {}", root, e.getMessage());
            return HostRootPolicy.DEFAULT_ROOT;
        }
    }

    private static String normalizeTag(String tag) {
        try {
            return AgentImageUtils.normalizeTag(tag);
        } catch (Exception e) {
            log.warn("旧版 Agent 镜像 tag「{}」不可用，迁移时回落到 latest: {}", tag, e.getMessage());
            return AgentImageUtils.DEFAULT_TAG;
        }
    }

    /**
     * 列是否存在：不同数据库大小写不一致，统一转小写比较；查询本身失败时返回 false。
     */
    private boolean hasColumn(String column) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns"
                            + " where lower(table_name) = 't_host' and lower(column_name) = ?",
                    Integer.class, column);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("查询 t_host.{} 是否存在失败: {}", column, e.toString());
            return false;
        }
    }

    /**
     * 不同数据库返回的列名大小写不一致，这里忽略大小写取值。
     */
    private static String text(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                Object value = entry.getValue();
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

}
