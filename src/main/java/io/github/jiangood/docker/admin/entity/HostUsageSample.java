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
 * 主机用量采样：采集任务每分钟写一行，只用于历史曲线，实时数字仍以内存快照为准。
 * <p>
 * 为什么只存主机级：逐容器历史是「容器数 × 行数」，一个月就能把内置 H2 顶满；
 * 主机级一分钟一行在 30 台主机、保留 30 天的规模下约 130 万行、200 MB 出头，可以接受。
 * <p>
 * 网络 / 块设备四个字段是 <b>累计计数器</b>（不是速率），与 docker stats 的原始口径一致：
 * 容器重启后计数器会归零，所以曲线上的速率由相邻采样求差得出，差值为负的那一段直接断开。
 */
@Remark("主机用量采样")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_host_usage_sample", indexes = {
        // 曲线查询：按主机取一段时间
        @Index(name = "idx_usage_sample_host", columnList = "host_id,sample_at"),
        // 清理：按时间整批删
        @Index(name = "idx_usage_sample_time", columnList = "sample_at")
})
public class HostUsageSample extends BaseEntity {

    private String hostId;

    /**
     * 主机名快照：主机被删除后曲线仍然可读。
     */
    private String hostName;

    /**
     * 采集完成时间（对应 {@code HostUsageVo.collectedAt}）。
     */
    private LocalDateTime sampleAt;

    /**
     * 容器占用的 CPU / 整机比例（%）。
     */
    private Double cpuPercent;

    /**
     * 容器占用的 CPU 核数（Σ 单容器 CPU% / 100）。
     */
    private Double cpuCoresUsed;

    private Long memUsed;

    /**
     * 主机内存容量，随每行快照保存，避免历史曲线因改配置而对不上。
     */
    private Long memoryTotal;

    private Double memPercent;

    /**
     * 网络累计接收 / 发送字节。
     */
    private Long netRx;
    private Long netTx;

    /**
     * 块设备累计读取 / 写入字节。
     */
    private Long blockRead;
    private Long blockWrite;

    private Integer containersRunning;

}
