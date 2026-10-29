package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.HostUsageSampleRepository;
import io.github.jiangood.docker.admin.dto.HostUsageRawPoint;
import io.github.jiangood.docker.admin.dto.HostUsageSeriesVo;
import io.github.jiangood.docker.admin.dto.HostUsageVo;
import io.github.jiangood.docker.admin.entity.HostUsageSample;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 主机用量采样：写入、清理与历史曲线查询。
 * <p>
 * 写入只在采集任务里进行（每分钟一批），读取只在主机详情的历史曲线里进行，
 * 与实时链路的 {@link HostUsageService}（内存快照）互不影响。
 */
@Slf4j
@Service
public class HostUsageSampleService {

    /**
     * 单次查询最多取多少条原始采样：保留期可配，先兜住内存，曲线本身也只画几百个点。
     */
    private static final int MAX_RAW_POINTS = 60_000;

    /**
     * 曲线目标点数：区间再长也只聚合成这么多桶，避免 30 天区间把 4 万个点丢给前端。
     */
    private static final int TARGET_BUCKETS = 300;

    /**
     * 清理频率：每分钟的采集任务里顺带跑，一小时检查一次足够。
     */
    private static final long PRUNE_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

    @Resource
    private HostUsageSampleRepository repository;

    /**
     * 采样保留天数：默认 30 天（改这里即可，也可以在配置里用 {@code docker.usage.retain-days} 覆盖）。
     * {@code <=0} 表示不清理——不建议，一分钟一行会把内置 H2 顶满。
     */
    @Value("${docker.usage.retain-days:30}")
    private int retainDays;

    private final AtomicLong lastPrune = new AtomicLong();

    /**
     * 批量写入一轮采集结果，只写采集成功的主机（失败的主机在曲线上留下断点，符合事实）。
     */
    @Transactional
    public int saveBatch(List<HostUsageVo> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        List<HostUsageSample> rows = new ArrayList<>(list.size());
        for (HostUsageVo vo : list) {
            if (vo == null || !vo.isReachable() || vo.getHostId() == null) {
                continue;
            }
            rows.add(toEntity(vo));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * 到点就清理过期采样；同一轮并发调用只有一次真正执行。
     *
     * @return 删除的条数
     */
    @Transactional
    public int pruneIfDue() {
        long now = System.currentTimeMillis();
        long last = lastPrune.get();
        if (last != 0 && now - last < PRUNE_INTERVAL_MILLIS) {
            return 0;
        }
        if (!lastPrune.compareAndSet(last, now)) {
            return 0;
        }
        return prune();
    }

    @Transactional
    public int prune() {
        if (retainDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        int deleted = repository.deleteBefore(cutoff);
        if (deleted > 0) {
            log.info("清理主机用量采样 {} 条（保留 {} 天）", deleted, retainDays);
        }
        return deleted;
    }

    /**
     * 历史曲线：区间内的采样点按时间分桶取平均，正反向速率由累计计数器求差得出。
     *
     * @param range {@code 1h} / {@code 6h} / {@code 24h} / {@code 7d} / {@code 30d}
     */
    public HostUsageSeriesVo series(String hostId, String range) {
        Range r = Range.parse(range);

        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minus(r.duration);
        List<HostUsageRawPoint> raw = repository.findPoints(
                hostId, from, to, PageRequest.of(0, MAX_RAW_POINTS));

        HostUsageSeriesVo vo = new HostUsageSeriesVo();
        vo.setHostId(hostId);
        vo.setRange(r.key);
        vo.setFrom(millis(from));
        vo.setTo(millis(to));
        vo.setBucketMillis(bucketMillis(r));
        vo.setSampleCount(raw.size());

        List<Sample> samples = toSamples(raw);
        vo.setPoints(aggregate(samples, vo.getBucketMillis()));
        return vo;
    }

    /**
     * 累计计数器 → 速率。差值为负说明容器重启导致计数器归零，该点留空让曲线断开，
     * 不做「补 0」或「截断」——那会把重启前后拼成一条假曲线。
     */
    private static List<Sample> toSamples(List<HostUsageRawPoint> raw) {
        List<Sample> samples = new ArrayList<>(raw.size());
        HostUsageRawPoint prev = null;
        for (HostUsageRawPoint point : raw) {
            long ts = millis(point.getSampleAt());
            double seconds = prev == null ? 0 : (ts - millis(prev.getSampleAt())) / 1000.0;
            samples.add(new Sample(
                    ts,
                    point.getCpuPercent(),
                    point.getMemPercent(),
                    asDouble(point.getMemUsed()),
                    point.getContainersRunning(),
                    rate(point.getNetRx(), prev == null ? null : prev.getNetRx(), seconds),
                    rate(point.getNetTx(), prev == null ? null : prev.getNetTx(), seconds),
                    rate(point.getBlockRead(), prev == null ? null : prev.getBlockRead(), seconds),
                    rate(point.getBlockWrite(), prev == null ? null : prev.getBlockWrite(), seconds)));
            prev = point;
        }
        return samples;
    }

    private static Double rate(Long current, Long previous, double seconds) {
        if (current == null || previous == null || seconds <= 0) {
            return null;
        }
        double delta = current - previous;
        return delta < 0 ? null : delta / seconds;
    }

    private static List<HostUsageSeriesVo.Point> aggregate(List<Sample> samples, long bucketMillis) {
        // 用有序 map：点位天然按时间升序，前端不必再排序
        Map<Long, List<Sample>> buckets = new LinkedHashMap<>();
        for (Sample sample : samples) {
            long key = sample.t - Math.floorMod(sample.t, bucketMillis);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);
        }

        List<HostUsageSeriesVo.Point> points = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, List<Sample>> entry : buckets.entrySet()) {
            List<Sample> list = entry.getValue();
            HostUsageSeriesVo.Point p = new HostUsageSeriesVo.Point();
            p.setT(entry.getKey());
            p.setCpuPercent(avg(list, Sample::cpuPercent));
            p.setMemPercent(avg(list, Sample::memPercent));
            p.setMemUsed(avg(list, Sample::memUsed));
            p.setNetRxBps(avg(list, Sample::netRxBps));
            p.setNetTxBps(avg(list, Sample::netTxBps));
            p.setReadBps(avg(list, Sample::readBps));
            p.setWriteBps(avg(list, Sample::writeBps));
            Double containers = avg(list, Sample::containers);
            p.setContainersRunning(containers == null ? null : (int) Math.round(containers));
            points.add(p);
        }
        return points;
    }

    private static Double avg(List<Sample> list, Function<Sample, ? extends Number> getter) {
        double sum = 0;
        int count = 0;
        for (Sample sample : list) {
            Number value = getter.apply(sample);
            if (value != null) {
                sum += value.doubleValue();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /**
     * 分桶粒度：最短 1 分钟（采集间隔），最长按「区间 / 目标点数」摊开。
     */
    private static long bucketMillis(Range range) {
        return Math.max(Duration.ofMinutes(1).toMillis(), range.duration.toMillis() / TARGET_BUCKETS);
    }

    private static HostUsageSample toEntity(HostUsageVo vo) {
        HostUsageSample row = new HostUsageSample();
        row.setHostId(vo.getHostId());
        row.setHostName(vo.getName());
        row.setSampleAt(toLocalDateTime(vo.getCollectedAt()));
        row.setCpuPercent(vo.getCpuPercent());
        row.setCpuCoresUsed(vo.getCpuCoresUsed());
        row.setMemUsed(vo.getMemUsed());
        row.setMemoryTotal(vo.getMemoryTotal());
        row.setMemPercent(vo.getMemPercent());
        row.setNetRx(vo.getNetRx());
        row.setNetTx(vo.getNetTx());
        row.setBlockRead(vo.getBlockRead());
        row.setBlockWrite(vo.getBlockWrite());
        row.setContainersRunning(vo.getContainersRunning());
        return row;
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static long millis(LocalDateTime time) {
        if (time == null) {
            return 0L;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Double asDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    private record Sample(long t,
                          Double cpuPercent,
                          Double memPercent,
                          Double memUsed,
                          Integer containers,
                          Double netRxBps,
                          Double netTxBps,
                          Double readBps,
                          Double writeBps) {
    }

    /**
     * 曲线支持的区间。只做 30 天以内：采样保留期也是 30 天，再长的区间没有数据可画。
     */
    public enum Range {
        H1("1h", Duration.ofHours(1)),
        H6("6h", Duration.ofHours(6)),
        H24("24h", Duration.ofHours(24)),
        D7("7d", Duration.ofDays(7)),
        D30("30d", Duration.ofDays(30));

        private final String key;
        private final Duration duration;

        Range(String key, Duration duration) {
            this.key = key;
            this.duration = duration;
        }

        public static Range parse(String value) {
            if (value != null && !value.isBlank()) {
                for (Range range : values()) {
                    if (range.key.equalsIgnoreCase(value.trim())) {
                        return range;
                    }
                }
            }
            throw new BusinessException("不支持的时间范围：" + value);
        }
    }
}
