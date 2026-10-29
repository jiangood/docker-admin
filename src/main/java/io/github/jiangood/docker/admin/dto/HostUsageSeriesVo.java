package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机用量历史曲线：按区间聚合后的点序列，点数上限 {@code ~300}，前端直接交给图表。
 * <p>
 * 数值字段一律用 {@code Double} / {@code long}：open-admin 会把包装类型 {@code Long}
 * 序列化成字符串（防 JS 丢精度），曲线要参与计算，用包装 Long 会逼着前端做转换。
 */
@Data
public class HostUsageSeriesVo {

    private String hostId;

    /**
     * 请求的区间标识，如 {@code 24h}。
     */
    private String range;

    /**
     * 区间起止（毫秒时间戳）。
     */
    private long from;
    private long to;

    /**
     * 每个点覆盖的时长（毫秒），前端的横轴刻度可据此格式化。
     */
    private long bucketMillis;

    /**
     * 区间内的原始采样条数，便于判断「曲线是否有数据」。
     */
    private int sampleCount;

    private List<Point> points = new ArrayList<>();

    @Data
    public static class Point {

        /**
         * 时间桶的起点（毫秒时间戳）。
         */
        private long t;

        private Double cpuPercent;

        private Double memPercent;

        /**
         * 容器内存合计（字节）。
         */
        private Double memUsed;

        /**
         * 网络速率（字节/秒）。计数器归零（容器重启）时为空，曲线在该点断开。
         */
        private Double netRxBps;
        private Double netTxBps;

        /**
         * 块设备读写速率（字节/秒）。
         */
        private Double readBps;
        private Double writeBps;

        private Integer containersRunning;
    }
}
