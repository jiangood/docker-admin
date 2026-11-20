package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 容器资源用量历史（按容器粒度，每 10 分钟采样一次，追加写入）。
 * <p>
 * 字段按 docker stats 口径冗余保存，与 {@link DockerStats} 保持同构；
 * 主机级趋势由同一时刻同一主机下的容器行聚合计算。
 */
@Remark("容器资源用量历史")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_docker_stats_his", indexes = {
        @Index(name = "idx_docker_stats_his_host_time", columnList = "host_id,collected_at"),
        @Index(name = "idx_docker_stats_his_time", columnList = "collected_at")
})
public class DockerStatsHis extends BaseEntity {

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
