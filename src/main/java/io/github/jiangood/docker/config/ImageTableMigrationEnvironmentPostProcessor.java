package io.github.jiangood.docker.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 镜像表迁移：在 Hibernate 建表/校验之前，把「镜像仓库」相关的旧结构自动迁移到「镜像」。
 * <p>
 * 迁移内容：
 * <ul>
 *     <li>{@code t_image_repo} → {@code t_image}；</li>
 *     <li>若存在更早版本遗留的旧 {@code t_image}（没有 {@code image_url} 列），先备份为
 *     {@code t_image_legacy} 再删除，为真正的镜像表让出表名（避免 "Column IMAGE_URL not found"）；</li>
 *     <li>{@code sys_role} 中的权限码 {@code image-repo:*} → {@code image:*}、菜单 id
 *     {@code image-repo} → {@code image}。</li>
 * </ul>
 * <p>
 * 迁移是幂等的：无需迁移时（全新安装、已完成迁移）直接跳过；失败只记录日志，不阻断启动，
 * 仍可用 {@code docs/migration/} 下的 SQL 手工处理。
 * <p>
 * 必须早于 Hibernate：否则 {@code ddl-auto=update} 会对遗留旧表执行
 * {@code alter table t_image add column image_url ... not null} 并因为已有数据而失败。
 */
public class ImageTableMigrationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PREFIX = "[image-migration] ";

    private static final String IMAGE_TABLE = "t_image";
    private static final String IMAGE_REPO_TABLE = "t_image_repo";
    private static final String IMAGE_URL_COLUMN = "image_url";
    private static final String IMAGE_LEGACY_TABLE = "t_image_legacy";
    private static final String ROLE_TABLE = "sys_role";

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor 之后，才能读到 application.yml 里的 spring.datasource.url
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:")) {
            return;
        }
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        loadDriver(environment.getProperty("spring.datasource.driver-class-name"));

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            migrate(connection);
        } catch (Exception e) {
            System.err.println(PREFIX + "自动迁移失败：" + e.getMessage());
            System.err.println(PREFIX + "未影响启动；可参考 docs/migration/image-repo-to-image-legacy-fix.sql 手工处理");
        }
    }

    private static void loadDriver(String driver) {
        if (driver == null) {
            return;
        }
        try {
            Class.forName(driver);
        } catch (Throwable ignored) {
            // 驱动一般由 JDBC ServiceLoader 自动加载，这里失败可忽略
        }
    }

    private void migrate(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();

        String repoTable = findTable(meta, IMAGE_REPO_TABLE);
        if (repoTable == null) {
            // 全新安装，或已完成迁移
            return;
        }

        String imageTable = findTable(meta, IMAGE_TABLE);
        if (imageTable == null) {
            rename(connection, repoTable, IMAGE_TABLE);
            System.out.println(PREFIX + repoTable + " → " + IMAGE_TABLE);
        } else if (!hasColumn(meta, imageTable, IMAGE_URL_COLUMN)) {
            // 更早版本遗留的旧「镜像」表（无 image_url）：备份后删除，让出表名
            String backup = uniqueTableName(meta, IMAGE_LEGACY_TABLE);
            backup(connection, imageTable, backup);
            drop(connection, imageTable);
            rename(connection, repoTable, IMAGE_TABLE);
            System.out.println(PREFIX + "遗留表 " + imageTable + " 已备份为 " + backup
                    + "，" + repoTable + " → " + IMAGE_TABLE);
        } else if (isEmpty(connection, imageTable) && !isEmpty(connection, repoTable)) {
            // 极端情况：t_image 已存在（空表）且有数据的 t_image_repo 还在
            drop(connection, imageTable);
            rename(connection, repoTable, IMAGE_TABLE);
            System.out.println(PREFIX + repoTable + " → " + IMAGE_TABLE);
        }

        migratePermissionCodes(connection, meta);
    }

    /**
     * 把角色里旧的 image-repo 权限码与菜单 id 替换为 image。
     */
    private void migratePermissionCodes(Connection connection, DatabaseMetaData meta) throws SQLException {
        String roleTable = findTable(meta, ROLE_TABLE);
        if (roleTable == null) {
            return;
        }
        boolean hasPerms = hasColumn(meta, roleTable, "perms");
        boolean hasMenus = hasColumn(meta, roleTable, "menus");
        if (!hasPerms && !hasMenus) {
            return;
        }

        int changed = 0;
        try (Statement st = connection.createStatement()) {
            if (hasPerms) {
                changed += st.executeUpdate("UPDATE " + roleTable
                        + " SET perms = REPLACE(perms, '\"image-repo:', '\"image:')"
                        + " WHERE perms LIKE '%\"image-repo:%'");
            }
            if (hasMenus) {
                changed += st.executeUpdate("UPDATE " + roleTable
                        + " SET menus = REPLACE(menus, '\"image-repo\"', '\"image\"')"
                        + " WHERE menus LIKE '%\"image-repo\"%'");
                changed += st.executeUpdate("UPDATE " + roleTable
                        + " SET menus = REPLACE(menus, '\"divider-image-repo\"', '\"divider-image\"')"
                        + " WHERE menus LIKE '%\"divider-image-repo\"%'");
            }
        }
        if (changed > 0) {
            System.out.println(PREFIX + "已更新 " + roleTable + " 中的旧权限码/菜单 id（" + changed + " 行）");
        }
    }

    // ------------------------------------------------------------------ 元数据

    private static String findTable(DatabaseMetaData meta, String tableName) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(tableName)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static boolean hasColumn(DatabaseMetaData meta, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String uniqueTableName(DatabaseMetaData meta, String baseName) throws SQLException {
        String name = baseName;
        int i = 2;
        while (findTable(meta, name) != null) {
            name = baseName + "_" + i++;
        }
        return name;
    }

    // ------------------------------------------------------------------ DDL

    private static boolean isEmpty(Connection connection, String table) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private static void backup(Connection connection, String table, String backupName) throws SQLException {
        execute(connection, "CREATE TABLE " + backupName + " AS SELECT * FROM " + table);
    }

    private static void drop(Connection connection, String table) throws SQLException {
        execute(connection, "DROP TABLE " + table);
    }

    private static void rename(Connection connection, String from, String to) throws SQLException {
        execute(connection, "ALTER TABLE " + from + " RENAME TO " + to);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

}
