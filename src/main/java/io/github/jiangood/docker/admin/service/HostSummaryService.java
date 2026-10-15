package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dto.HostInfoVo;
import io.github.jiangood.docker.admin.dto.HostSummaryVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 首页「主机资源汇总」看板：把所有纳管主机的 CPU / 内存 / 容器 / 镜像聚合起来。
 * <p>
 * 两个必须处理的现实问题：
 * <ul>
 *   <li><b>慢主机不能拖住整页</b>——每台主机都要现连 docker，SSH 主机还要握手，
 *       所以逐台并行查询并各自套 {@link DockerClientManager#callWithTimeout} 超时兜底。</li>
 *   <li><b>首页是落地页</b>——每次打开都重连所有主机没有意义，因此结果做短期缓存；
 *       用户点「刷新」可强制绕过缓存。</li>
 * </ul>
 */
@Slf4j
@Service
public class HostSummaryService {

    /**
     * 单台主机查询超时。取 5 秒：SSH 握手 + docker info 正常在 1~2 秒内完成，
     * 超过这个时间基本可判定该主机不可用，没必要让用户继续等。
     */
    private static final Duration PER_HOST_TIMEOUT = Duration.ofSeconds(5);

    /** 汇总结果缓存时长，避免反复进出首页时重复建立连接。 */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    @Resource
    private HostService hostService;

    @Resource
    private HostDockerService hostDockerService;

    @Resource
    private DockerClientManager dockerClientManager;

    /**
     * 查各主机的线程池。用平台线程而非虚拟线程：docker-java / JSch 内部大量 synchronized，
     * 虚拟线程会被固定到载体线程（与 {@link DockerClientManager} 的取舍一致）。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "host-summary");
        thread.setDaemon(true);
        return thread;
    });

    private volatile HostSummaryVo cache;
    private volatile long cacheTime;

    /**
     * @param refresh 为 true 时忽略缓存重新汇总
     */
    public HostSummaryVo summary(boolean refresh) {
        if (!refresh && isFresh()) {
            return cache;
        }
        // 加锁：并发请求（多标签页、多人同时进首页）只需真正汇总一次，其余等结果即可
        synchronized (this) {
            if (!refresh && isFresh()) {
                return cache;
            }
            HostSummaryVo vo = load();
            cache = vo;
            cacheTime = System.currentTimeMillis();
            return vo;
        }
    }

    private boolean isFresh() {
        return cache != null && System.currentTimeMillis() - cacheTime < CACHE_TTL.toMillis();
    }

    private HostSummaryVo load() {
        List<Host> hosts = hostService.findAll(Spec.of(), Sort.by(Host.Fields.name));

        HostSummaryVo vo = new HostSummaryVo();
        vo.setHostTotal(hosts.size());

        List<Future<HostSummaryVo.Item>> futures = new ArrayList<>(hosts.size());
        for (Host host : hosts) {
            futures.add(executor.submit(() -> query(host)));
        }

        for (int i = 0; i < hosts.size(); i++) {
            Host host = hosts.get(i);
            HostSummaryVo.Item item;
            try {
                // 比内部超时多留 1 秒余量，正常情况下不会走到这里
                item = futures.get(i).get(PER_HOST_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.get(i).cancel(true);
                item = unreachable(host, "查询被中断");
            } catch (Exception e) {
                futures.get(i).cancel(true);
                item = unreachable(host, "查询超时");
            }

            vo.getHosts().add(item);
            if (!item.isReachable()) {
                continue;
            }
            vo.setHostReachable(vo.getHostReachable() + 1);
            vo.setCpuTotal(vo.getCpuTotal() + nvl(item.getCpus()));
            vo.setMemoryTotal(vo.getMemoryTotal() + nvl(item.getMemory()));
            vo.setContainersRunning(vo.getContainersRunning() + nvl(item.getContainersRunning()));
            vo.setContainersStopped(vo.getContainersStopped() + nvl(item.getContainersStopped()));
            vo.setContainersTotal(vo.getContainersTotal() + nvl(item.getContainersTotal()));
            vo.setImagesTotal(vo.getImagesTotal() + nvl(item.getImages()));
        }
        return vo;
    }

    private HostSummaryVo.Item query(Host host) {
        try {
            HostInfoVo info = dockerClientManager.callWithTimeout(
                    host, PER_HOST_TIMEOUT, client -> hostDockerService.info(client, host));

            HostSummaryVo.Item item = new HostSummaryVo.Item();
            item.setId(host.getId());
            item.setName(host.getName());
            item.setConnectionType(host.getConnectionType());
            item.setDockerHost(host.getDockerHost());
            item.setReachable(true);
            item.setCpus(info.getCpus());
            item.setMemory(info.getMemory());
            item.setContainersRunning(info.getContainersRunning());
            item.setContainersStopped(info.getContainersStopped());
            item.setContainersTotal(info.getContainersTotal());
            item.setImages(info.getImages());
            return item;
        } catch (Exception e) {
            log.warn("首页汇总：主机 {} 不可用：{}", host.getName(), e.getMessage());
            return unreachable(host, e.getMessage());
        }
    }

    private static HostSummaryVo.Item unreachable(Host host, String error) {
        HostSummaryVo.Item item = new HostSummaryVo.Item();
        item.setId(host.getId());
        item.setName(host.getName());
        item.setConnectionType(host.getConnectionType());
        item.setDockerHost(host.getDockerHost());
        item.setReachable(false);
        item.setError(error);
        return item;
    }

    private static long nvl(Integer v) {
        return v == null ? 0L : v;
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
