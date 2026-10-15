package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页「主机资源汇总」看板数据。
 * <p>
 * 参考 Rancher 首页：顶部是全局资源概览，下面是逐节点的资源明细。
 * <p>
 * 汇总口径：所有合计项都<b>只统计可连接的主机</b>，否则任一台离线主机都会把总数拖成 0，
 * 让看板失去意义；离线主机数通过 {@link #hostTotal} 与 {@link #hostReachable} 的差值体现。
 * <p>
 * 只展示静态容量（CPU 核数、内存容量），不含实时使用率——实时使用率需要 docker stats
 * 持续轮询，代价远高于首页能承受的范围。
 */
@Data
public class HostSummaryVo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主机总数 */
    long hostTotal;
    /** 可连接的主机数 */
    long hostReachable;

    /** CPU 总核数 */
    long cpuTotal;
    /** 内存总容量（字节） */
    long memoryTotal;

    /** 运行中的容器数 */
    long containersRunning;
    /** 已停止的容器数 */
    long containersStopped;
    /** 容器总数 */
    long containersTotal;
    /** 镜像总数 */
    long imagesTotal;

    /** 逐主机明细（含不可连接的主机，按名称排序） */
    List<Item> hosts = new ArrayList<>();

    /**
     * 单台主机的资源明细。
     */
    @Data
    public static class Item implements Serializable {
        private static final long serialVersionUID = 1L;

        String id;
        String name;
        String connectionType;
        String dockerHost;

        /** 是否成功读取到主机信息 */
        boolean reachable;
        /** 不可连接时的原因，可直接展示 */
        String error;

        /** CPU 核数 */
        Integer cpus;
        /** 内存容量（字节） */
        Long memory;
        Integer containersRunning;
        Integer containersStopped;
        Integer containersTotal;
        Integer images;
    }
}
