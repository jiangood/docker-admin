package io.github.jiangood.docker.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 一次性数据搬迁：主机的自定义请求头值列从 {@code auth_header} 改名为 {@code header_value}。
 * <p>
 * 为什么要这个类：Hibernate 的 {@code ddl-auto=update} 只会新增列、不会改名，改名之后老库会变成
 * 「新列 header_value 空着 + 旧列 auth_header 留着老值」，已配好的请求头会静默失效（代理直接 401），
 * 而且不会有任何报错。这里在启动后把旧列的值搬到新列。
 * <p>
 * 幂等：只搬 {@code header_value} 为空的记录，搬完再启动等于空转；全新库没有旧列，直接跳过。
 * 旧列 {@code auth_header} 不删（Hibernate 不会删，也没必要动它），留着不影响使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostHeaderColumnMigration {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        // 明确查不到旧列（全新库）就直接跳过；查不出表结构（null）时仍然试一把，交给下面的 try 兜底
        if (Boolean.FALSE.equals(hasColumn("auth_header"))) {
            return;
        }
        try {
            int rows = jdbcTemplate.update("update t_host set header_value = auth_header"
                    + " where header_value is null and auth_header is not null");
            if (rows > 0) {
                log.info("自定义请求头值已从旧列 auth_header 搬到 header_value，共 {} 台主机", rows);
            }
        } catch (Exception e) {
            // 老列不存在等情况：没有东西可搬，不影响启动
            log.debug("跳过自定义请求头值搬迁: {}", e.toString());
        }
    }

    /**
     * 列是否存在：不同数据库大小写不一致，统一转小写比较；查询本身失败时返回 null。
     */
    private Boolean hasColumn(String column) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns"
                            + " where lower(table_name) = 't_host' and lower(column_name) = ?",
                    Integer.class, column);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("查询表结构失败，按「需要搬迁」处理: {}", e.toString());
            return null;
        }
    }
}
