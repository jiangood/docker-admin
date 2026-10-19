package io.github.jiangood.docker.config;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖镜像表自动迁移的各种历史库状态（H2 内存库，MySQL 兼容模式，与生产默认库一致）。
 */
class ImageTableMigrationEnvironmentPostProcessorTest {

    private final ImageTableMigrationEnvironmentPostProcessor processor =
            new ImageTableMigrationEnvironmentPostProcessor();

    @Test
    void renamesRepoTableWhenNoLegacyImageExists() throws Exception {
        try (Connection c = open("repo_only")) {
            exec(c, "CREATE TABLE t_image_repo (id varchar(64) primary key, image_url varchar(400) not null unique, name varchar(255))");
            exec(c, "INSERT INTO t_image_repo(id, image_url, name) VALUES ('1','registry.cn/ns/nginx','nginx')");

            processor.migrate(c);

            assertNotNull(findTable(c, "t_image"));
            assertNull(findTable(c, "t_image_repo"));
            assertTrue(hasColumn(c, "t_image", "image_url"));
            assertEquals(1, count(c, "t_image"));
        }
    }

    @Test
    void retiresLegacyImageTableWhenRepoIsAbsent() throws Exception {
        // 从 4.7 之前直升上来的库：只有旧 t_image，没有 t_image_repo
        try (Connection c = open("legacy_only")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, name varchar(100) not null, git_url varchar(255))");
            exec(c, "INSERT INTO t_image(id, name, git_url) VALUES ('1','nginx','https://git/x.git')");

            processor.migrate(c);

            // 旧表改名保留数据，把 t_image 表名让给 Hibernate 重建
            assertNull(findTable(c, "t_image"));
            assertNotNull(findTable(c, "t_image_legacy"));
            assertEquals(1, count(c, "t_image_legacy"));
        }
    }

    @Test
    void handlesLegacyImageReferencedByForeignKey() throws Exception {
        // 历史版本 t_app 曾对 t_image 建过外键：DROP 会被外键挡住，改名则可正常让位
        try (Connection c = open("legacy_with_fk")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, name varchar(100) not null, git_url varchar(255))");
            exec(c, "CREATE TABLE t_image_repo (id varchar(64) primary key, image_url varchar(400) not null unique, name varchar(255))");
            exec(c, "CREATE TABLE t_app (id varchar(64) primary key, image_id varchar(64), "
                    + "constraint fk_app_image foreign key (image_id) references t_image(id))");
            exec(c, "INSERT INTO t_image(id, name) VALUES ('1','legacy')");
            exec(c, "INSERT INTO t_image_repo(id, image_url) VALUES ('r1','registry.cn/ns/nginx')");

            processor.migrate(c);

            assertTrue(hasColumn(c, "t_image", "image_url"));
            assertNull(findTable(c, "t_image_repo"));
            assertEquals(1, count(c, "t_image"));
            assertTrue(hasColumn(c, "t_image_legacy", "git_url"));

            // 旧外键自动跟随改名的旧表，仍然可用
            exec(c, "INSERT INTO t_app(id, image_id) VALUES ('a1','1')");
            assertEquals(1, count(c, "t_app"));
        }
    }

    @Test
    void replacesEmptyImageWithRepo() throws Exception {
        try (Connection c = open("empty_image")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, image_url varchar(400) not null unique)");
            exec(c, "CREATE TABLE t_image_repo (id varchar(64) primary key, image_url varchar(400) not null unique)");
            exec(c, "INSERT INTO t_image_repo(id, image_url) VALUES ('r1','registry.cn/ns/nginx')");

            processor.migrate(c);

            assertEquals(1, count(c, "t_image"));
            assertEquals("registry.cn/ns/nginx", queryString(c, "SELECT image_url FROM t_image"));
            assertNull(findTable(c, "t_image_repo"));
        }
    }

    @Test
    void keepsMigratedImageAndFixesPermissionsWithoutRepo() throws Exception {
        try (Connection c = open("perms_only")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, image_url varchar(400) not null unique)");
            exec(c, "CREATE TABLE sys_role (id varchar(64) primary key, perms varchar(4000), menus varchar(4000))");
            exec(c, "INSERT INTO sys_role(id, perms, menus) VALUES ('1', "
                    + "'[\"image-repo:view\"]', '[\"image-repo\",\"divider-image-repo\"]')");

            processor.migrate(c);

            assertEquals("[\"image:view\"]", queryString(c, "SELECT perms FROM sys_role WHERE id='1'"));
            assertEquals("[\"image\",\"divider-image\"]", queryString(c, "SELECT menus FROM sys_role WHERE id='1'"));
            assertNotNull(findTable(c, "t_image"));
            assertNull(findTable(c, "t_image_legacy"));
        }
    }

    @Test
    void keepsExistingBackupWhenRetiringAgain() throws Exception {
        try (Connection c = open("legacy_suffix")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, name varchar(100))");
            exec(c, "CREATE TABLE t_image_legacy (id varchar(64) primary key)");

            processor.migrate(c);

            assertNotNull(findTable(c, "t_image_legacy_2"));
        }
    }

    @Test
    void isIdempotent() throws Exception {
        try (Connection c = open("idempotent")) {
            exec(c, "CREATE TABLE t_image (id varchar(64) primary key, name varchar(100))");
            exec(c, "INSERT INTO t_image(id, name) VALUES ('1','legacy')");
            exec(c, "CREATE TABLE t_image_repo (id varchar(64) primary key, image_url varchar(400) not null unique)");
            exec(c, "INSERT INTO t_image_repo(id, image_url) VALUES ('r1','registry.cn/ns/nginx')");

            processor.migrate(c);
            processor.migrate(c);

            assertNull(findTable(c, "t_image_legacy_2"));
            assertEquals(1, count(c, "t_image"));
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Connection open(String name) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String findTable(Connection c, String name) throws SQLException {
        return ImageTableMigrationEnvironmentPostProcessor.findTable(c.getMetaData(), name);
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        return ImageTableMigrationEnvironmentPostProcessor.hasColumn(c.getMetaData(), table, column);
    }

}
