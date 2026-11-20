package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 容器用量最新快照：采集任务每轮写入，只保留每台主机上每个运行容器的最近一次采样。
 * <p>
 * 与 {@link HostUsageSample}（主机级历史曲线）不同，这里不存时序：应用详情、容器详情、
 * 首页 TopN 等页面只需要“当前”CPU/内存等指标，按主机整体替换即可，数据量恒定（≈运行容器数）。
 * <p>
 * 主机采集失败 / 退避跳过时会清空该主机的快照，避免页面读到过期容器。
 */
@Remark("容器用量快照")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_container_usage",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_container_usage_host_container",
                        columnNames = {"host_id", "container_id"})
        },
        indexes = {
                @Index(name = "idx_container_usage_host", columnList = "host_id"),
                @Index(name = "idx_container_usage_app", columnList = "app_id"),
                @Index(name = "idx_container_usage_mem", columnList = "mem_usage")
        })
public class ContainerUsage extends BaseEntity {

    private String hostId;

    /**
     * 主机名快照，便于列表展示。
     */
    private String hostName;

    private String containerId;

    private String containerName;

    private String image;

    /**
     * 关联应用 id（按容器 {@code app.name} 标签在该主机上匹配），非应用容器为空。
     */
    private String appId;

    /**
     * 应用名快照，便于展示。
     */
    private String appName;

    /**
     * CPU 使用率（%），按 docker CLI 口径，单容器可超过 100%。
     */
    private Double cpuPercent;

    /**
     * 内存用量（字节，已扣除 inactive_file）。
     */
    private Long memUsage;

    /**
     * 内存上限（字节）。
     */
    private Long memLimit;

    /**
     * 内存使用率（%）。
     */
    private Double memPercent;

    private Long netRx;

    private Long netTx;

    private Long blockRead;

    private Long blockWrite;

    private Long pids;

    /**
     * 单容器采集失败原因；有值时其余用量字段可能为空。
     */
    private String error;

    /**
     * 本轮采集完成时间。
     */
    private LocalDateTime collectedAt;

}
