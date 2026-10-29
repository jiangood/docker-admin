package io.github.jiangood.docker.admin.dto;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 采样行的查询投影：只取画曲线需要的几个数值列，不加载整条实体。
 * <p>
 * 独立成类而不是 record：JPQL 的构造器表达式按位置匹配构造参数，普通类最没有歧义。
 * 30 天区间单主机有 4 万多行，投影能把加载量压到实体的一小半。
 */
@Getter
public class HostUsageRawPoint {

    private final LocalDateTime sampleAt;
    private final Double cpuPercent;
    private final Double memPercent;
    private final Long memUsed;
    private final Integer containersRunning;
    private final Long netRx;
    private final Long netTx;
    private final Long blockRead;
    private final Long blockWrite;

    public HostUsageRawPoint(LocalDateTime sampleAt,
                             Double cpuPercent,
                             Double memPercent,
                             Long memUsed,
                             Integer containersRunning,
                             Long netRx,
                             Long netTx,
                             Long blockRead,
                             Long blockWrite) {
        this.sampleAt = sampleAt;
        this.cpuPercent = cpuPercent;
        this.memPercent = memPercent;
        this.memUsed = memUsed;
        this.containersRunning = containersRunning;
        this.netRx = netRx;
        this.netTx = netTx;
        this.blockRead = blockRead;
        this.blockWrite = blockWrite;
    }
}
