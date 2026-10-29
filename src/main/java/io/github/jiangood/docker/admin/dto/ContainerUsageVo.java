package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 单个容器的实时用量，等价于 {@code docker stats} 的一行。
 * <p>
 * 数据来自 Docker Engine 的 {@code GET /containers/{id}/stats?stream=false}，
 * 口径与 docker CLI 保持一致：CPU 百分比按「核」计（100% = 占满一个核，8 核主机上单容器可到 800%），
 * 内存用量扣掉 inactive_file。
 * <p>
 * 采集失败时只填 {@link #error}，其余字段为空，不影响同主机其他容器。
 */
@Data
public class ContainerUsageVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String id;
    String name;
    String image;

    /** CPU 使用率（%），无前一次采样（如刚启动）时为空 */
    Double cpuPercent;
    /** 内存用量（字节，已按 docker stats 口径扣除 inactive_file） */
    Long memUsage;
    /** 内存上限（字节）：未限制时等于主机内存 */
    Long memLimit;
    /** 内存使用率（%） */
    Double memPercent;
    /** 网络累计接收（字节） */
    Long netRx;
    /** 网络累计发送（字节） */
    Long netTx;
    /** 块设备累计读取（字节） */
    Long blockRead;
    /** 块设备累计写入（字节） */
    Long blockWrite;
    /** 进程数 */
    Long pids;

    /** 采集失败原因；有值时其余用量字段为空 */
    String error;
}
