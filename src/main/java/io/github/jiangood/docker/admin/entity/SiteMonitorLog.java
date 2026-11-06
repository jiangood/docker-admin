package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 网站监控检测记录：每次探测落一条，供页面「检测历史」抽屉回看。
 * <p>
 * 与 {@link SiteMonitor} 一样只存监控项名快照，监控项被删除后记录仍可读；
 * 记录默认保留 30 天，由检查任务顺带清理（见 {@code SiteMonitorService#pruneLogsIfDue}）。
 */
@Remark("网站监控检测记录")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_site_monitor_log")
public class SiteMonitorLog extends BaseEntity {

    /**
     * 对应的监控项 id。监控项删除时其记录一并删除（见 SiteMonitorService#deleteMonitor）。
     */
    String monitorId;

    /**
     * 监控项名称快照。
     */
    String monitorName;

    /**
     * 本次检测成功与否（含状态码与关键词判定）。
     */
    Boolean success;

    /**
     * HTTP 状态码；请求都没发出去（连接超时等）时为 null。
     */
    Integer statusCode;

    /**
     * 耗时（毫秒）。
     */
    Long latencyMs;

    /**
     * 失败原因，成功时为空；检测到反向关键词时附命中片段，方便确认是不是代理层占位页。
     */
    @Column(length = 1000)
    String error;

}
