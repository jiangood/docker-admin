package io.github.jiangood.docker.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 一次性表结构修正：把 {@code t_deploy_log.trigger_type} 从数据库原生 ENUM 改成 VARCHAR。
 * <p>
 * 为什么要这个类：Hibernate 6.2 起 {@code @Enumerated(EnumType.STRING)} 默认映射成数据库原生 ENUM
 * （H2 / MySQL 都是），而 {@code ddl-auto=update} 只新增列、不会给已有 ENUM 列补新取值。
 * 新增触发方式「定时更新（SCHEDULE）」后，老库插入部署记录会被数据库拒绝，表现为：
 * 定时检查显示「发现新版本，开始部署」，但部署日志里没有任何记录，应用一直停在「部署中」。
 * <p>
 * 实体上已用 {@code @JdbcTypeCode(SqlTypes.VARCHAR)} 固定为 VARCHAR，全新库不会再建 ENUM 列；
 * 这里负责把老库的 ENUM 列改过来，之后再加触发方式就不需要动表结构了。
 * <p>
 * 幂等：列已经是 VARCHAR 时直接跳过；查不到表结构（如建表尚未完成）也只记 debug 日志，不影响启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployLogTriggerTypeMigration {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        String dataType = columnDataType();
        if (dataType == null || !"enum".equalsIgnoreCase(dataType)) {
            return;
        }

        try {
            DataSource dataSource = jdbcTemplate.getDataSource();
            String product = dataSource == null ? "" : databaseProduct(dataSource).toLowerCase();
            if (product.contains("mysql") || product.contains("mariadb")) {
                jdbcTemplate.execute("alter table t_deploy_log modify column trigger_type varchar(32)");
            } else {
                // H2 2.x：ALTER COLUMN ... SET DATA TYPE 会把已有 ENUM 值转换成字符串
                jdbcTemplate.execute("alter table t_deploy_log alter column trigger_type set data type varchar(32)");
            }
            log.info("部署记录的触发方式列已从 ENUM 改为 VARCHAR，后续新增触发方式不再需要改表");
        } catch (Exception e) {
            log.warn("部署记录的触发方式列从 ENUM 改为 VARCHAR 失败，定时更新可能写不进部署记录：{}", e.toString());
        }
    }

    private String databaseProduct(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 列的数据类型：不同数据库大小写不一致，统一按小写比较；查询本身失败时返回 null。
     */
    private String columnDataType() {
        try {
            return jdbcTemplate.queryForObject(
                    "select data_type from information_schema.columns"
                            + " where lower(table_name) = 't_deploy_log' and lower(column_name) = 'trigger_type'",
                    String.class);
        } catch (Exception e) {
            log.debug("查询 t_deploy_log 表结构失败，跳过触发方式列迁移: {}", e.toString());
            return null;
        }
    }
}
