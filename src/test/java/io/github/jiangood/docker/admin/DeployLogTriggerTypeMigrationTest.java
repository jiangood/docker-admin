package io.github.jiangood.docker.admin;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 老库的 {@code trigger_type} 是原生 ENUM，新增 SCHEDULE 后插不进去；
 * 迁移要把它改成 VARCHAR，且重复执行不报错。
 */
class DeployLogTriggerTypeMigrationTest {

    private JdbcTemplate newJdbcTemplate(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return new JdbcTemplate(dataSource);
    }

    @Test
    void enumColumnIsConvertedSoNewTriggerValuesCanBeInserted() {
        JdbcTemplate jdbc = newJdbcTemplate("deploy-log-enum");
        jdbc.execute("create table t_deploy_log ("
                + " id varchar(36) primary key,"
                + " trigger_type enum('BUILD','CONFIG','CREATE','MANUAL','MIGRATE','RENAME'))");
        jdbc.update("insert into t_deploy_log (id, trigger_type) values ('old', 'MANUAL')");

        DeployLogTriggerTypeMigration migration = new DeployLogTriggerTypeMigration(jdbc);
        migration.migrate();

        assertEquals("CHARACTER VARYING", columnDataType(jdbc).toUpperCase());
        assertEquals("MANUAL", jdbc.queryForObject(
                "select trigger_type from t_deploy_log where id = 'old'", String.class));

        // 迁移前会被数据库拒绝
        jdbc.update("insert into t_deploy_log (id, trigger_type) values ('new', 'SCHEDULE')");
        assertEquals("SCHEDULE", jdbc.queryForObject(
                "select trigger_type from t_deploy_log where id = 'new'", String.class));

        // 幂等：再跑一次不应报错
        migration.migrate();
        assertFalse("ENUM".equalsIgnoreCase(columnDataType(jdbc)));
    }

    @Test
    void varcharColumnIsLeftUntouched() {
        JdbcTemplate jdbc = newJdbcTemplate("deploy-log-varchar");
        jdbc.execute("create table t_deploy_log (id varchar(36) primary key, trigger_type varchar(32))");
        jdbc.update("insert into t_deploy_log (id, trigger_type) values ('new', 'SCHEDULE')");

        new DeployLogTriggerTypeMigration(jdbc).migrate();

        assertEquals("CHARACTER VARYING", columnDataType(jdbc).toUpperCase());
        assertEquals("SCHEDULE", jdbc.queryForObject(
                "select trigger_type from t_deploy_log where id = 'new'", String.class));
    }

    private String columnDataType(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select data_type from information_schema.columns"
                        + " where lower(table_name) = 't_deploy_log' and lower(column_name) = 'trigger_type'",
                String.class);
    }
}
