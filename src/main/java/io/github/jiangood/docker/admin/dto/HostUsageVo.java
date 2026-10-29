package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单台主机的实时用量，等价于 {@code docker stats} 的主机级汇总。
 * <p>
 * 与 {@link HostSummaryVo}（静态容量）分开：这个 VO 的数据每次都要现连 docker，
 * 每台主机、每个运行中的容器各一次 {@code /stats}，单轮耗时以秒计，只适合异步加载 + 缓存。
 * <p>
 * 汇总口径：CPU / 内存都只统计「运行中的容器」，因此是「容器合计」而不是整机真实占用
 * （容器之外的进程、内核、页面缓存共享都会让数字偏低，前端需注明）。
 */
@Data
public class HostUsageVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String hostId;
    String name;

    /** 是否成功采集 */
    boolean reachable;
    /** 采集失败原因，可直接展示 */
    String error;

    /** CPU 核数（来自 docker info） */
    Integer cpus;
    /** 主机内存容量（字节） */
    Long memoryTotal;
    /** 运行中的容器数 */
    Integer containersRunning;

    /** 容器占用的 CPU 核数（Σ 单容器 CPU% / 100） */
    Double cpuCoresUsed;
    /** 容器占用的 CPU 占整机比例（%） */
    Double cpuPercent;
    /** 容器内存合计（字节） */
    Long memUsed;
    /** 容器内存合计占主机内存比例（%） */
    Double memPercent;
    /** 网络累计接收 / 发送（字节） */
    Long netRx;
    Long netTx;
    /** 块设备累计读取 / 写入（字节） */
    Long blockRead;
    Long blockWrite;

    /** 本次采集耗时（毫秒） */
    Long durationMillis;
    /** 采集完成时间（毫秒时间戳） */
    Long collectedAt;

    /** 逐容器明细（仅主机详情接口下发） */
    List<ContainerUsageVo> containers = new ArrayList<>();

    /**
     * 首页只需要主机级数字：返回去掉容器明细的副本，避免把整份明细重复下发。
     */
    public HostUsageVo toBrief() {
        HostUsageVo vo = new HostUsageVo();
        vo.setHostId(hostId);
        vo.setName(name);
        vo.setReachable(reachable);
        vo.setError(error);
        vo.setCpus(cpus);
        vo.setMemoryTotal(memoryTotal);
        vo.setContainersRunning(containersRunning);
        vo.setCpuCoresUsed(cpuCoresUsed);
        vo.setCpuPercent(cpuPercent);
        vo.setMemUsed(memUsed);
        vo.setMemPercent(memPercent);
        vo.setNetRx(netRx);
        vo.setNetTx(netTx);
        vo.setBlockRead(blockRead);
        vo.setBlockWrite(blockWrite);
        vo.setDurationMillis(durationMillis);
        vo.setCollectedAt(collectedAt);
        return vo;
    }
}
