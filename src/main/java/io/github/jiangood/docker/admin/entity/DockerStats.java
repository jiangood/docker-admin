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
 * 容器资源用量最新快照（按容器粒度，每轮采集整体覆盖）。
 * <p>
 * 字段按 docker stats 口径冗余保存，与 {@link DockerStatsHis} 保持同构，
 * 便于“当前值”查询与统一展示。
 */
@Remark("容器资源用量快照")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_docker_stats",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_docker_stats_host_container",
                        columnNames = {"host_id", "container_id"})
        },
        indexes = {
                @Index(name = "idx_docker_stats_host", columnList = "host_id"),
                @Index(name = "idx_docker_stats_app", columnList = "app_id"),
                @Index(name = "idx_docker_stats_mem", columnList = "mem_usage")
        })
public class DockerStats extends BaseEntity {

    private String hostId;
    private String hostName;

    /** 冗余的主机容量信息，便于聚合/展示 */
    private Integer hostCpus;
    private Long hostMemoryTotal;

    private String containerId;
    private String containerName;
    private String image;

    /** 关联应用（按容器 app.name 标签匹配），非应用容器为空 */
    private String appId;
    private String appName;

    /** 采集时间 */
    private LocalDateTime collectedAt;

    /** docker stats: CPU % */
    private Double cpuPercent;
    /** docker stats: MEM USAGE（字节，已扣除 inactive_file） */
    private Long memUsage;
    /** docker stats: MEM LIMIT（字节） */
    private Long memLimit;
    /** docker stats: MEM % */
    private Double memPercent;
    /** docker stats: NET I/O 累计接收（字节） */
    private Long netRx;
    /** docker stats: NET I/O 累计发送（字节） */
    private Long netTx;
    /** docker stats: BLOCK I/O 累计读取（字节） */
    private Long blockRead;
    /** docker stats: BLOCK I/O 累计写入（字节） */
    private Long blockWrite;
    /** docker stats: PIDS */
    private Long pids;

    /** 单容器采集失败原因；有值时其余用量字段可能为空 */
    private String error;

}
