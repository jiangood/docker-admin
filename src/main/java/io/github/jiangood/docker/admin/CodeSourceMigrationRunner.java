package io.github.jiangood.docker.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 兼容升级：把旧「Git 凭据」（表 t_git_credential / 权限 git-credential）迁移到「代码源」。
 * <p>
 * 新库不存在旧表时直接跳过；已存在旧表时搬数据、补类型，最后删除旧表（幂等，可重复执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeSourceMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        migrateRolePermissions();
        migrateCredentialTable();
    }

    /**
     * 角色权限码/菜单 id 由 git-credential / setting-git 改为 code-source / setting-code-source。
     */
    private void migrateRolePermissions() throws Exception {
        if (!tableExists("sys_role")) {
            return;
        }
        int perms = jdbcTemplate.update(
                "update sys_role set perms = replace(perms, 'git-credential:', 'code-source:') "
                        + "where perms like '%git-credential:%'");
        int menus = jdbcTemplate.update(
                "update sys_role set menus = replace(menus, 'setting-git', 'setting-code-source') "
                        + "where menus like '%setting-git%'");
        if (perms > 0 || menus > 0) {
            log.info("已迁移角色权限引用：perms {} 行，menus {} 行", perms, menus);
        }
    }

    private void migrateCredentialTable() throws Exception {
        if (!tableExists("t_git_credential") || !tableExists("t_code_source")) {
            return;
        }

        Long newCount = jdbcTemplate.queryForObject("select count(*) from t_code_source", Long.class);
        Long oldCount = jdbcTemplate.queryForObject("select count(*) from t_git_credential", Long.class);
        if ((newCount == null || newCount == 0) && oldCount != null && oldCount > 0) {
            Set<String> newColumns = new LinkedHashSet<>(columns("t_code_source"));
            List<String> common = new ArrayList<>();
            for (String column : columns("t_git_credential")) {
                if (newColumns.contains(column)) {
                    common.add(column);
                }
            }
            if (common.isEmpty()) {
                log.warn("旧表 t_git_credential 与新表无公共列，跳过数据迁移");
            } else {
                String columnList = String.join(", ", common);
                int count = jdbcTemplate.update(
                        "insert into t_code_source (" + columnList + ") select " + columnList + " from t_git_credential");
                log.info("已从 t_git_credential 迁移 {} 条代码源记录", count);
            }
        } else if (newCount != null && newCount > 0) {
            log.info("t_code_source 已有数据，跳过数据复制");
        }

        // 补类型（幂等）：注意 type 为枚举列，不能用 = '' 比较
        jdbcTemplate.update("update t_code_source set type = case "
                + "when lower(url) like '%gitlab%' then 'GITLAB' "
                + "when lower(url) like '%gitee%' then 'GITEE' "
                + "when lower(url) like '%github%' then 'GITHUB' "
                + "else 'CUSTOM' end "
                + "where type is null");

        jdbcTemplate.execute("drop table t_git_credential");
        log.info("旧表 t_git_credential 已删除");
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", null)) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<String> columns(String table) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, null, "%", "%")) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            return columns;
        }
    }
}
