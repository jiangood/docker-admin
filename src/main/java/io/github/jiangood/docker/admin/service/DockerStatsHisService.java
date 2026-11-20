package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.DockerStatsHisRepository;
import io.github.jiangood.docker.admin.dto.ContainerUsageVo;
import io.github.jiangood.docker.admin.dto.HostUsageSeriesVo;
import io.github.jiangood.docker.admin.dto.HostUsageVo;
import io.github.jiangood.docker.admin.entity.DockerStatsHis;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 容器用量历史（t_docker_stats_his，按容器粒度，每 10 分钟采样一次）：写入、清理与趋势查询。
 * <p>
 * 主机级趋势由同一时刻同一主机下的容器历史行聚合计算。
 */
@Slf4j
@Service
public class DockerStatsHisService {

    /** 历史采样间隔：每 10 分钟写一次 */
    private static final long HISTORY_INTERVAL_MINUTES = 10;

    /** 单次查询最多取多少条容器历史行，避免大范围查询撑爆内存 */
    private static final int MAX_RAW_ROWS = 200_000;

    private static final int TARGET_BUCKETS = 300;

    private static final long PRUNE_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

    @Resource
    private DockerStatsHisRepository repository;

    /**
     * 历史保留天数，默认 7 天（容器粒度数据量大于主机聚合历史）。
     */
    @Value("${docker.stats.his.retain-days:7}")
    private int retainDays;

    private final AtomicLong lastPrune = new AtomicLong();

    /** 上次已写入历史的 10 分钟时间桶（毫秒），用于避免同一桶重复写入 */
    private final AtomicLong lastHistoryBucket = new AtomicLong();

    /**
     * 判断本轮采集是否为 10 分钟历史采样点（不改变状态）。
     */
    public boolean isHistoryRound(Long collectedAtMillis) {
        if (collectedAtMillis == null) {
            return false;
        }
        return bucketKey(collectedAtMillis) > lastHistoryBucket.get();
    }

    /**
     * 标记某个时间点所属的 10 分钟桶已写入历史。
     */
    public void markHistoryWritten(Long collectedAtMillis) {
        if (collectedAtMillis == null) {
            return;
        }
        long bucket = bucketKey(collectedAtMillis);
        lastHistoryBucket.updateAndGet(prev -> Math.max(prev, bucket));
    }

    /**
     * 写入一轮可达主机的容器历史（按容器粒度）。
     */
    @Transactional
    public int saveBatch(List<HostUsageVo> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        List<DockerStatsHis> rows = new ArrayList<>();
        for (HostUsageVo vo : list) {
            if (vo == null || !vo.isReachable() || !StringUtils.hasText(vo.getHostId())) {
                continue;
            }
            LocalDateTime at = toLocalDateTime(vo.getCollectedAt());
            List<ContainerUsageVo> containers = vo.getContainers();
            if (containers == null) {
                continue;
            }
            for (ContainerUsageVo c : containers) {
                if (c == null || !StringUtils.hasText(c.getId())) {
                    continue;
                }
                rows.add(toEntity(c, vo, at));
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        repository.saveAll(rows);
        return rows.size();
    }

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
            log.info("清理容器用量历史 {} 条（保留 {} 天）", deleted, retainDays);
        }
        return deleted;
    }

    /**
     * 主机历史趋势：按容器历史行聚合为主机级时间序列，输出结构与前端图表保持兼容。
     */
    public HostUsageSeriesVo series(String hostId, String range) {
        Range r = Range.parse(range);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minus(r.duration);

        List<DockerStatsHis> rows = repository.findByHostIdAndTimeRange(
                hostId, from, to, PageRequest.of(0, MAX_RAW_ROWS));

        HostUsageSeriesVo vo = new HostUsageSeriesVo();
        vo.setHostId(hostId);
        vo.setRange(r.key);
        vo.setFrom(millis(from));
        vo.setTo(millis(to));
        vo.setBucketMillis(bucketMillis(r));
        vo.setSampleCount(countSampleTimes(rows));

        List<HostSample> samples = aggregateByTime(rows);
        vo.setPoints(bucketize(samples, vo.getBucketMillis()));
        return vo;
    }

    /**
     * 将同一主机、同一采集时间的容器行聚合为主机级采样点。
     */
    private static List<HostSample> aggregateByTime(List<DockerStatsHis> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DockerStatsHis>> groups = new LinkedHashMap<>();
        for (DockerStatsHis row : rows) {
            if (row == null || row.getCollectedAt() == null) {
                continue;
            }
            long t = millis(row.getCollectedAt());
            groups.computeIfAbsent(t, k -> new ArrayList<>()).add(row);
        }

        List<Long> times = new ArrayList<>(groups.keySet());
        times.sort(Long::compareTo);

        List<HostSample> samples = new ArrayList<>(times.size());
        HostSample prev = null;
        for (Long t : times) {
            List<DockerStatsHis> list = groups.get(t);
            HostAgg agg = aggregateHost(list);
            double seconds = prev == null ? 0 : (t - prev.t) / 1000.0;

            HostSample s = new HostSample(
                    t,
                    agg.cpuPercent,
                    agg.memPercent,
                    agg.memUsed == null ? null : agg.memUsed.doubleValue(),
                    agg.containersRunning,
                    rate(agg.netRx, prev == null ? null : prev.netRx, seconds),
                    rate(agg.netTx, prev == null ? null : prev.netTx, seconds),
                    rate(agg.blockRead, prev == null ? null : prev.blockRead, seconds),
                    rate(agg.blockWrite, prev == null ? null : prev.blockWrite, seconds),
                    agg.netRx, agg.netTx, agg.blockRead, agg.blockWrite
            );
            samples.add(s);
            prev = s;
        }
        return samples;
    }

    private static HostAgg aggregateHost(List<DockerStatsHis> list) {
        double cores = 0;
        boolean hasCpu = false;
        long mem = 0;
        boolean hasMem = false;
        long netRx = 0;
        long netTx = 0;
        long blockRead = 0;
        long blockWrite = 0;
        int containers = 0;
        Integer hostCpus = null;
        Long hostMemoryTotal = null;

        for (DockerStatsHis r : list) {
            if (r == null) continue;
            containers++;
            if (hostCpus == null) hostCpus = r.getHostCpus();
            if (hostMemoryTotal == null) hostMemoryTotal = r.getHostMemoryTotal();

            if (r.getCpuPercent() != null) {
                cores += r.getCpuPercent() / 100.0;
                hasCpu = true;
            }
            if (r.getMemUsage() != null) {
                mem += r.getMemUsage();
                hasMem = true;
            }
            netRx += nvl(r.getNetRx());
            netTx += nvl(r.getNetTx());
            blockRead += nvl(r.getBlockRead());
            blockWrite += nvl(r.getBlockWrite());
        }

        HostAgg a = new HostAgg();
        a.containersRunning = containers;
        a.cpuPercent = (hasCpu && hostCpus != null && hostCpus > 0)
                ? Math.min(100, round(cores / hostCpus * 100, 1)) : null;
        a.memUsed = hasMem ? mem : null;
        a.memPercent = (hasMem && hostMemoryTotal != null && hostMemoryTotal > 0)
                ? Math.min(100, round((double) mem / hostMemoryTotal * 100, 1)) : null;
        a.netRx = netRx;
        a.netTx = netTx;
        a.blockRead = blockRead;
        a.blockWrite = blockWrite;
        return a;
    }

    private static Double rate(Long current, Long previous, double seconds) {
        if (current == null || previous == null || seconds <= 0) {
            return null;
        }
        double delta = current - previous;
        return delta < 0 ? null : delta / seconds;
    }

    private static List<HostUsageSeriesVo.Point> bucketize(List<HostSample> samples, long bucketMillis) {
        Map<Long, List<HostSample>> buckets = new LinkedHashMap<>();
        for (HostSample sample : samples) {
            long key = sample.t - Math.floorMod(sample.t, bucketMillis);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);
        }
        List<HostUsageSeriesVo.Point> points = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, List<HostSample>> entry : buckets.entrySet()) {
            List<HostSample> list = entry.getValue();
            HostUsageSeriesVo.Point p = new HostUsageSeriesVo.Point();
            p.setT(entry.getKey());
            p.setCpuPercent(avg(list, s -> s.cpuPercent));
            p.setMemPercent(avg(list, s -> s.memPercent));
            p.setMemUsed(avg(list, s -> s.memUsed));
            p.setNetRxBps(avg(list, s -> s.netRxBps));
            p.setNetTxBps(avg(list, s -> s.netTxBps));
            p.setReadBps(avg(list, s -> s.readBps));
            p.setWriteBps(avg(list, s -> s.writeBps));
            Double containers = avg(list, s -> s.containersRunning == null ? null : s.containersRunning.doubleValue());
            p.setContainersRunning(containers == null ? null : (int) Math.round(containers));
            points.add(p);
        }
        return points;
    }

    private static Double avg(List<HostSample> list, Function<HostSample, ? extends Number> getter) {
        double sum = 0;
        int count = 0;
        for (HostSample sample : list) {
            Number value = getter.apply(sample);
            if (value != null) {
                sum += value.doubleValue();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private static long bucketMillis(Range range) {
        return Math.max(Duration.ofMinutes(10).toMillis(), range.duration.toMillis() / TARGET_BUCKETS);
    }

    private static int countSampleTimes(List<DockerStatsHis> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        long count = rows.stream().map(DockerStatsHis::getCollectedAt).filter(java.util.Objects::nonNull).distinct().count();
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private static DockerStatsHis toEntity(ContainerUsageVo c, HostUsageVo host, LocalDateTime at) {
        DockerStatsHis e = new DockerStatsHis();
        e.setHostId(host.getHostId());
        e.setHostName(host.getName());
        e.setHostCpus(host.getCpus());
        e.setHostMemoryTotal(host.getMemoryTotal());
        e.setContainerId(c.getId());
        e.setContainerName(c.getName());
        e.setImage(c.getImage());
        e.setAppId(c.getAppId());
        e.setAppName(c.getAppName());
        e.setCollectedAt(at);
        e.setCpuPercent(c.getCpuPercent());
        e.setMemUsage(c.getMemUsage());
        e.setMemLimit(c.getMemLimit());
        e.setMemPercent(c.getMemPercent());
        e.setNetRx(c.getNetRx());
        e.setNetTx(c.getNetTx());
        e.setBlockRead(c.getBlockRead());
        e.setBlockWrite(c.getBlockWrite());
        e.setPids(c.getPids());
        e.setError(c.getError());
        return e;
    }

    private static long bucketKey(long epochMillis) {
        long interval = Duration.ofMinutes(HISTORY_INTERVAL_MINUTES).toMillis();
        return epochMillis - Math.floorMod(epochMillis, interval);
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static long millis(LocalDateTime time) {
        if (time == null) return 0L;
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private enum Range {
        H1("1h", Duration.ofHours(1)),
        H6("6h", Duration.ofHours(6)),
        H24("24h", Duration.ofHours(24)),
        D7("7d", Duration.ofDays(7)),
        D30("30d", Duration.ofDays(30));

        final String key;
        final Duration duration;

        Range(String key, Duration duration) {
            this.key = key;
            this.duration = duration;
        }

        static Range parse(String value) {
            if (value != null) {
                for (Range r : values()) {
                    if (r.key.equalsIgnoreCase(value)) return r;
                }
            }
            return H24;
        }
    }

    private static class HostAgg {
        Double cpuPercent;
        Double memPercent;
        Long memUsed;
        Integer containersRunning;
        Long netRx;
        Long netTx;
        Long blockRead;
        Long blockWrite;
    }

    private static class HostSample {
        final long t;
        final Double cpuPercent;
        final Double memPercent;
        final Double memUsed;
        final Integer containersRunning;
        final Double netRxBps;
        final Double netTxBps;
        final Double readBps;
        final Double writeBps;
        final Long netRx;
        final Long netTx;
        final Long blockRead;
        final Long blockWrite;

        HostSample(long t, Double cpuPercent, Double memPercent, Double memUsed, Integer containersRunning,
                   Double netRxBps, Double netTxBps, Double readBps, Double writeBps,
                   Long netRx, Long netTx, Long blockRead, Long blockWrite) {
            this.t = t;
            this.cpuPercent = cpuPercent;
            this.memPercent = memPercent;
            this.memUsed = memUsed;
            this.containersRunning = containersRunning;
            this.netRxBps = netRxBps;
            this.netTxBps = netTxBps;
            this.readBps = readBps;
            this.writeBps = writeBps;
            this.netRx = netRx;
            this.netTx = netTx;
            this.blockRead = blockRead;
            this.blockWrite = blockWrite;
        }
    }
}
