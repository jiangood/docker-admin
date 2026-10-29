package io.github.jiangood.docker.admin.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.BlkioStatEntry;
import com.github.dockerjava.api.model.BlkioStatsConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatsConfig;
import io.github.jiangood.docker.admin.dto.ContainerUsageVo;
import io.github.jiangood.docker.admin.dto.HostUsageVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 主机实时用量：等价于 {@code docker stats} 的采集与汇总。
 * <p>
 * 为什么单独一个服务（而不是塞进 {@link HostSummaryService}）：
 * <ul>
 *   <li>静态容量（{@code docker info}）很快，实时用量（每台主机、每个运行中容器各一次
 *       {@code /stats}）要慢一个数量级，两者的超时、缓存与降级策略完全不同；</li>
 *   <li>{@code /stats?stream=0} 在 docker 守护进程内部要采两个样本（间隔约 1 秒）才有 CPU 百分比，
 *       所以「一轮采集 ≈ 1 秒起」是硬开销，只能靠并发 + 缓存分摊。</li>
 * </ul>
 * 采集策略：每台主机复用同一个 {@link DockerClient}（SSH 是一条 Session 多 Channel），
 * 容器级任务放进固定线程池，超时只影响单个容器；主机级用
 * {@link DockerClientManager#callWithTimeout} 兜住传输层卡死。
 * <p>
 * 缓存：每主机 3 秒，命中即返回；过期则采集（同一主机的并发请求共享同一个在途任务）。
 * <p>
 * 数据来源两条链路：
 * <ul>
 *   <li><b>定时任务</b>（{@code HostUsageCollectJob}，每分钟）调 {@link #collectAll()} 采一轮、
 *       写缓存，并交回结果由 {@code HostUsageSampleService} 落库，供主机详情的历史曲线使用；</li>
 *   <li><b>页面请求</b>只读快照（{@link #snapshotAll()} / {@link #snapshot(String)}），不触发采集，
 *       所以开多少个标签页都不会增加主机侧负载；只有用户点「刷新」才走 {@link #usageAll(boolean)} 现采。</li>
 * </ul>
 */
@Slf4j
@Service
public class HostUsageService {

    /** 单台主机一轮采集的总预算（含建立连接、info、容器列表与全部 stats）。 */
    private static final Duration PER_HOST_TIMEOUT = Duration.ofSeconds(8);

    /** 主机内部等待容器结果的预算（从进入采集算起，含 info / 容器列表的耗时）。 */
    private static final long CONTAINER_BUDGET_MILLIS = 6_000;

    /** 单容器采集超时：dockerd 的两次采样本身约 1 秒，留足余量。 */
    private static final long PER_CONTAINER_TIMEOUT_MILLIS = 4_000;

    /** 结果缓存时长：比前端 5 秒轮询稍短，保证每轮都能拿到新数据，又能合并同时到达的请求。 */
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);

    @Resource
    private HostService hostService;

    @Resource
    private DockerClientManager dockerClientManager;

    /** 主机级任务：会阻塞等待容器结果，用缓存线程池避免主机之间互相排队。 */
    private final ExecutorService hostExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "host-usage");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 容器 stats 线程池。固定大小：每个请求在 dockerd 内部都要等一次采样周期，
     * 并发再高只会把主机压垮，不会更快。
     */
    private final ExecutorService statsExecutor = Executors.newFixedThreadPool(12, r -> {
        Thread thread = new Thread(r, "container-stats");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 同一主机的在途采集：并发的首页/详情请求只真正采一次。 */
    private final Map<String, CompletableFuture<HostUsageVo>> inflight = new ConcurrentHashMap<>();

    /**
     * 连续采集失败的主机退避表：只在定时任务里生效。
     * <p>
     * 一台宕机的 SSH 主机每轮采集都要握手、等超时（最长 8 秒），不退避就是每分钟踢一次铁板；
     * 而「刷新」按钮仍然直接采集，不受这里的限制。
     * <p>
     * 状态不能放在 Quartz 任务里：任务每次触发都会新建实例（见 open-admin 的 BaseJob 说明）。
     */
    private final Map<String, Backoff> backoff = new ConcurrentHashMap<>();

    /**
     * 所有主机的实时用量（首页表格用，不含容器明细）。
     *
     * @param refresh 为 true 时忽略缓存强制重新采集
     */
    public List<HostUsageVo> usageAll(boolean refresh) {
        List<Host> hosts = hostService.findAll(Spec.of(), Sort.by(Host.Fields.name));

        List<Future<HostUsageVo>> futures = new ArrayList<>(hosts.size());
        for (Host host : hosts) {
            futures.add(hostExecutor.submit(() -> usage(host, refresh)));
        }

        List<HostUsageVo> result = new ArrayList<>(hosts.size());
        long deadline = System.currentTimeMillis() + PER_HOST_TIMEOUT.toMillis() + 4_000;
        for (int i = 0; i < hosts.size(); i++) {
            Host host = hosts.get(i);
            try {
                long remain = Math.max(500, deadline - System.currentTimeMillis());
                result.add(futures.get(i).get(remain, TimeUnit.MILLISECONDS).toBrief());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.get(i).cancel(true);
                result.add(failed(host, "采集被中断", 0).toBrief());
            } catch (Exception e) {
                futures.get(i).cancel(true);
                result.add(failed(host, "采集超时", 0).toBrief());
            }
        }
        return result;
    }

    /**
     * 单台主机的实时用量（主机详情用，含逐容器明细）。
     *
     * @param refresh 为 true 时忽略缓存强制重新采集
     */
    public HostUsageVo usage(String hostId, boolean refresh) {
        Host host = hostService.findById(hostId)
                .orElseThrow(() -> new BusinessException("主机不存在"));
        return usage(host, refresh);
    }

    /**
     * 定时任务的采集入口：无视缓存与 TTL 采一轮，写缓存并把成功的结果交回调用方落库。
     * <p>
     * 与页面请求共用 {@link #collect(Host)}，所以和手动「刷新」撞上时只会真正采一次。
     */
    public CollectResult collectAll() {
        List<Host> hosts = hostService.findAll(Spec.of(), Sort.by(Host.Fields.name));
        pruneCache(hosts);
        long start = System.currentTimeMillis();

        List<Future<HostUsageVo>> futures = new ArrayList<>(hosts.size());
        for (Host host : hosts) {
            futures.add(hostExecutor.submit(() -> inBackoff(host) ? null : collect(host)));
        }

        List<HostUsageVo> values = new ArrayList<>(hosts.size());
        int skipped = 0;
        int failed = 0;
        long deadline = System.currentTimeMillis() + PER_HOST_TIMEOUT.toMillis() + 4_000;
        for (int i = 0; i < hosts.size(); i++) {
            Host host = hosts.get(i);
            HostUsageVo vo;
            try {
                long remain = Math.max(500, deadline - System.currentTimeMillis());
                vo = futures.get(i).get(remain, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.get(i).cancel(true);
                vo = failed(host, "采集被中断", 0);
            } catch (Exception e) {
                futures.get(i).cancel(true);
                vo = failed(host, "采集超时", 0);
            }
            if (vo == null) {
                // 退避中：本轮不采，也不写采样，曲线上就是断点
                skipped++;
                continue;
            }
            if (!vo.isReachable()) {
                failed++;
            }
            values.add(vo);
        }

        return new CollectResult(values, hosts.size(), values.size() - failed, failed, skipped,
                System.currentTimeMillis() - start);
    }

    /**
     * 首页用的只读快照：返回所有已有缓存的主机（brief 形态），不发起采集。
     * <p>
     * 还没有缓存的主机（刚启动、刚从退避里恢复）异步预热一次，页面下一次轮询就能拿到，
     * 但不让本次请求等它。
     */
    public List<HostUsageVo> snapshotAll() {
        List<Host> hosts = hostService.findAll(Spec.of(), Sort.by(Host.Fields.name));
        pruneCache(hosts);

        List<HostUsageVo> result = new ArrayList<>(hosts.size());
        for (Host host : hosts) {
            CacheEntry entry = cache.get(host.getId());
            if (entry == null) {
                prewarm(host);
                continue;
            }
            result.add(entry.value().toBrief());
        }
        return result;
    }

    /**
     * 主机详情用的只读快照：缓存没有时返回 {@code null}（并异步预热一次），由前端展示「等待采集」。
     */
    public HostUsageVo snapshot(String hostId) {
        Host host = hostService.findById(hostId)
                .orElseThrow(() -> new BusinessException("主机不存在"));
        CacheEntry entry = cache.get(hostId);
        if (entry == null) {
            prewarm(host);
            return null;
        }
        return entry.value();
    }

    /**
     * 异步预热一台主机的缓存：只用于「页面第一次打开时缓存还是空的」这一种情况。
     */
    private void prewarm(Host host) {
        if (inBackoff(host) || inflight.containsKey(host.getId())) {
            return;
        }
        hostExecutor.submit(() -> {
            try {
                collect(host);
            } catch (Exception e) {
                log.debug("实时用量预热失败：主机 {}：{}", host.getName(), e.toString());
            }
        });
    }

    /**
     * 移除已删除主机的缓存与退避状态，避免长期运行后残留。
     */
    private void pruneCache(List<Host> hosts) {
        if (cache.isEmpty() && backoff.isEmpty()) {
            return;
        }
        Set<String> alive = hosts.stream().map(Host::getId).collect(Collectors.toSet());
        cache.keySet().removeIf(id -> !alive.contains(id));
        backoff.keySet().removeIf(id -> !alive.contains(id));
    }

    /**
     * 记录一次采集结果：成功清零退避，失败按阶梯拉长重试间隔。
     */
    private void recordOutcome(Host host, HostUsageVo vo) {
        if (vo.isReachable()) {
            backoff.remove(host.getId());
            return;
        }
        Backoff old = backoff.getOrDefault(host.getId(), new Backoff(0, 0));
        int failures = old.failures() + 1;
        backoff.put(host.getId(), new Backoff(failures, System.currentTimeMillis() + backoffDelay(failures)));
    }

    private boolean inBackoff(Host host) {
        Backoff state = backoff.get(host.getId());
        return state != null && System.currentTimeMillis() < state.nextAttemptAt();
    }

    /**
     * 退避阶梯：第一次失败下一轮（1 分钟）照常重试，之后 2 / 5 / 15 分钟。
     */
    private static long backoffDelay(int failures) {
        return switch (failures) {
            case 0, 1 -> 0L;
            case 2 -> Duration.ofMinutes(2).toMillis();
            case 3 -> Duration.ofMinutes(5).toMillis();
            default -> Duration.ofMinutes(15).toMillis();
        };
    }

    /**
     * 一轮定时采集的汇总结果。
     *
     * @param values 实际采到的主机（含失败的主机，{@code reachable=false}），退避跳过的不在其中
     */
    public record CollectResult(List<HostUsageVo> values,
                                int hosts,
                                int success,
                                int failed,
                                int skipped,
                                long durationMillis) {

        public String describe() {
            return "采集 " + hosts + " 台：成功 " + success + "、失败 " + failed
                    + (skipped > 0 ? "、退避跳过 " + skipped : "")
                    + "，耗时 " + String.format("%.1f", durationMillis / 1000.0) + " 秒";
        }
    }

    private record Backoff(int failures, long nextAttemptAt) {
    }

    private HostUsageVo usage(Host host, boolean refresh) {
        CacheEntry entry = cache.get(host.getId());
        if (!refresh && entry != null
                && System.currentTimeMillis() - entry.time < CACHE_TTL.toMillis()) {
            return entry.value;
        }
        return collect(host);
    }

    /**
     * 采集并写缓存，同一主机只允许一个在途任务，其余等待它的结果。
     */
    private HostUsageVo collect(Host host) {
        CompletableFuture<HostUsageVo> mine = new CompletableFuture<>();
        CompletableFuture<HostUsageVo> running = inflight.putIfAbsent(host.getId(), mine);
        if (running != null) {
            try {
                return running.get(PER_HOST_TIMEOUT.toMillis() + 3_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed(host, "采集被中断", 0);
            } catch (Exception e) {
                return failed(host, "采集超时", 0);
            }
        }

        long start = System.currentTimeMillis();
        try {
            HostUsageVo vo = dockerClientManager.callWithTimeout(host, PER_HOST_TIMEOUT,
                    client -> collectWithClient(host, client));
            vo.setDurationMillis(System.currentTimeMillis() - start);
            vo.setCollectedAt(System.currentTimeMillis());
            cache.put(host.getId(), new CacheEntry(vo, System.currentTimeMillis()));
            recordOutcome(host, vo);
            mine.complete(vo);
            return vo;
        } catch (Exception e) {
            log.warn("实时用量：主机 {} 采集失败：{}", host.getName(), e.toString());
            HostUsageVo vo = failed(host, rootMessage(e), System.currentTimeMillis() - start);
            cache.put(host.getId(), new CacheEntry(vo, System.currentTimeMillis()));
            recordOutcome(host, vo);
            mine.complete(vo);
            return vo;
        } finally {
            inflight.remove(host.getId(), mine);
        }
    }

    private HostUsageVo collectWithClient(Host host, DockerClient client) {
        // 预算从进入采集算起：info / 列表慢的时候，留给容器 stats 的时间相应变少，
        // 保证整体仍在外层 PER_HOST_TIMEOUT 内收口
        long deadline = System.currentTimeMillis() + CONTAINER_BUDGET_MILLIS;

        HostUsageVo vo = new HostUsageVo();
        vo.setHostId(host.getId());
        vo.setName(host.getName());

        Info info = client.infoCmd().exec();
        vo.setCpus(info.getNCPU());
        vo.setMemoryTotal(info.getMemTotal());

        // 只统计运行中的容器：已停止的容器没有用量，逐个 stats 只是白等 1 秒
        List<Container> containers = client.listContainersCmd().withShowAll(false).exec();
        vo.setContainersRunning(containers.size());
        vo.setReachable(true);

        List<Future<ContainerUsageVo>> futures = new ArrayList<>(containers.size());
        for (Container container : containers) {
            futures.add(statsExecutor.submit(() -> containerUsage(client, container, info.getNCPU())));
        }

        for (int i = 0; i < containers.size(); i++) {
            Container container = containers.get(i);
            Future<ContainerUsageVo> future = futures.get(i);
            ContainerUsageVo usage;
            try {
                long remain = deadline - System.currentTimeMillis();
                // 预算用完就不再等；已经算完的仍然直接取结果
                if (remain <= 0 && !future.isDone()) {
                    throw new TimeoutException();
                }
                usage = future.get(Math.max(1, remain), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                usage = failedUsage(container, "采集被中断");
            } catch (TimeoutException e) {
                // 不打断在跑的任务：stats 自身有 4 秒超时，打断反而可能把中断标志带进下一个池内任务
                future.cancel(false);
                usage = failedUsage(container, "采集超时");
            } catch (Exception e) {
                usage = failedUsage(container, rootMessage(e));
            }
            vo.getContainers().add(usage);
        }

        aggregate(vo);
        return vo;
    }

    private ContainerUsageVo containerUsage(DockerClient client, Container container, Integer hostCpus) {
        ContainerUsageVo vo = new ContainerUsageVo();
        vo.setId(container.getId());
        vo.setName(firstName(container.getNames()));
        vo.setImage(container.getImage());
        try {
            Statistics statistics = stats(client, container.getId());
            fill(vo, statistics, hostCpus);
        } catch (Exception e) {
            vo.setError(rootMessage(e));
        }
        return vo;
    }

    /**
     * 取一次 stats 快照（{@code stream=0}）。
     * <p>
     * 不用 {@code stream=1}：持续流会一直占着连接与线程，页面只关心当前这一刻。
     * {@code stream=0} 时 docker 会在守护进程内采两次样本（间隔约 1 秒）再返回，
     * 这样 {@code precpu_stats} 有值，CPU 百分比才算得出来。
     */
    private Statistics stats(DockerClient client, String containerId) {
        AtomicReference<Statistics> holder = new AtomicReference<>();
        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics statistics) {
                holder.set(statistics);
            }
        };

        try {
            client.statsCmd(containerId).withNoStream(true).exec(callback);
            if (!callback.awaitCompletion(PER_CONTAINER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("采集超时");
            }
            Statistics statistics = holder.get();
            if (statistics == null) {
                throw new IllegalStateException("docker 未返回统计数据");
            }
            return statistics;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("采集被中断", e);
        }
    }

    /**
     * 按 docker CLI 的口径换算容器用量。
     */
    private static void fill(ContainerUsageVo vo, Statistics statistics, Integer hostCpus) {
        // CPU% = (cpuDelta / systemDelta) * onlineCpus * 100
        CpuStatsConfig cpu = statistics.getCpuStats();
        CpuStatsConfig pre = statistics.getPreCpuStats();
        Long total = totalUsage(cpu);
        Long preTotal = totalUsage(pre);
        Long system = cpu == null ? null : cpu.getSystemCpuUsage();
        Long preSystem = pre == null ? null : pre.getSystemCpuUsage();
        if (total != null && preTotal != null && system != null && preSystem != null && system > preSystem) {
            long cpuDelta = Math.max(0, total - preTotal);
            long systemDelta = system - preSystem;
            long cores = positive(cpu.getOnlineCpus(), hostCpus);
            vo.setCpuPercent((double) cpuDelta / systemDelta * cores * 100.0);
        }

        MemoryStatsConfig memory = statistics.getMemoryStats();
        if (memory != null) {
            Long usage = memory.getUsage();
            Long limit = memory.getLimit();
            if (usage != null) {
                // 与 docker stats 一致：扣掉可回收的 inactive_file（cgroup v1 total_inactive_file / v2 inactive_file）
                Long inactive = inactiveFile(memory.getStats());
                if (inactive != null && inactive > 0 && inactive < usage) {
                    usage -= inactive;
                }
                vo.setMemUsage(usage);
                if (limit != null && limit > 0) {
                    vo.setMemPercent(usage * 100.0 / limit);
                }
            }
            vo.setMemLimit(limit);
        }

        Map<String, StatisticNetworksConfig> networks =
                statistics.getNetworks() != null ? statistics.getNetworks() : statistics.getNetwork();
        if (networks != null) {
            long rx = 0;
            long tx = 0;
            for (StatisticNetworksConfig network : networks.values()) {
                rx += nvl(network.getRxBytes());
                tx += nvl(network.getTxBytes());
            }
            vo.setNetRx(rx);
            vo.setNetTx(tx);
        }

        // 块 I/O：docker stats 在没有 I/O 时显示 0B，因此有列表按 0 起算；整个 blkio 缺失也按 0 处理
        long blockRead = 0;
        long blockWrite = 0;
        BlkioStatsConfig blkio = statistics.getBlkioStats();
        if (blkio != null && blkio.getIoServiceBytesRecursive() != null) {
            for (BlkioStatEntry entry : blkio.getIoServiceBytesRecursive()) {
                if (entry.getValue() == null || entry.getOp() == null) {
                    continue;
                }
                if ("read".equalsIgnoreCase(entry.getOp())) {
                    blockRead += entry.getValue();
                } else if ("write".equalsIgnoreCase(entry.getOp())) {
                    blockWrite += entry.getValue();
                }
            }
        }
        vo.setBlockRead(blockRead);
        vo.setBlockWrite(blockWrite);

        if (statistics.getPidsStats() != null) {
            vo.setPids(statistics.getPidsStats().getCurrent());
        }
    }

    private static void aggregate(HostUsageVo vo) {
        double cores = 0;
        boolean hasCpu = false;
        long mem = 0;
        boolean hasMem = false;
        long netRx = 0;
        long netTx = 0;
        long blockRead = 0;
        long blockWrite = 0;

        for (ContainerUsageVo item : vo.getContainers()) {
            if (item.getCpuPercent() != null) {
                cores += item.getCpuPercent() / 100.0;
                hasCpu = true;
            }
            if (item.getMemUsage() != null) {
                mem += item.getMemUsage();
                hasMem = true;
            }
            netRx += nvl(item.getNetRx());
            netTx += nvl(item.getNetTx());
            blockRead += nvl(item.getBlockRead());
            blockWrite += nvl(item.getBlockWrite());
        }

        vo.setCpuCoresUsed(hasCpu ? round(cores, 2) : null);
        Integer cpus = vo.getCpus();
        vo.setCpuPercent(hasCpu && cpus != null && cpus > 0
                ? Math.min(100, round(cores / cpus * 100, 1))
                : null);

        vo.setMemUsed(hasMem ? mem : null);
        Long total = vo.getMemoryTotal();
        vo.setMemPercent(hasMem && total != null && total > 0
                ? Math.min(100, round((double) mem / total * 100, 1))
                : null);

        vo.setNetRx(netRx);
        vo.setNetTx(netTx);
        vo.setBlockRead(blockRead);
        vo.setBlockWrite(blockWrite);
    }

    private static HostUsageVo failed(Host host, String error, long durationMillis) {
        HostUsageVo vo = new HostUsageVo();
        vo.setHostId(host.getId());
        vo.setName(host.getName());
        vo.setReachable(false);
        vo.setError(error);
        vo.setDurationMillis(durationMillis);
        vo.setCollectedAt(System.currentTimeMillis());
        return vo;
    }

    private static ContainerUsageVo failedUsage(Container container, String error) {
        ContainerUsageVo vo = new ContainerUsageVo();
        vo.setId(container.getId());
        vo.setName(firstName(container.getNames()));
        vo.setImage(container.getImage());
        vo.setError(error);
        return vo;
    }

    private static Long totalUsage(CpuStatsConfig cpu) {
        return cpu == null || cpu.getCpuUsage() == null ? null : cpu.getCpuUsage().getTotalUsage();
    }

    private static Long inactiveFile(StatsConfig stats) {
        if (stats == null) {
            return null;
        }
        Long totalInactive = stats.getTotalInactiveFile();
        return totalInactive != null ? totalInactive : stats.getInactiveFile();
    }

    private static long positive(Long primary, Integer fallback) {
        if (primary != null && primary > 0) {
            return primary;
        }
        return fallback == null || fallback < 1 ? 1 : fallback;
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0 || names[0] == null) {
            return null;
        }
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private record CacheEntry(HostUsageVo value, long time) {
    }
}
