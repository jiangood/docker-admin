package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.service.HostAgentService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主机 CLI 会话注册表：把 {@link HostCliSession}（docker exec 出来的 shell）从 WebSocket 连接里拆出来，
 * 让浏览器断开只是取消订阅，命令继续跑。
 * <p>
 * 生命周期：
 * <ul>
 *     <li>创建：第一次连上某个 key 时建立 shell；</li>
 *     <li>断开：WebSocket 关闭只清订阅者，shell 与输出缓冲都保留；</li>
 *     <li>回收：后台线程每 60 秒清理「无订阅者且空闲超过 30 分钟」的会话，
 *     以及 shell 自己退出（用户输入 {@code exit}）的会话；</li>
 *     <li>停机：{@link #shutdown} 关闭全部 shell 与 Docker 客户端。</li>
 * </ul>
 * 每个用户最多同时保留 {@link #MAX_SESSIONS_PER_USER} 个会话，超出时淘汰最久未用的已断开会话，
 * 防止反复开标签页把 shell 堆积在目标主机上。
 */
@Slf4j
@Component
public class HostCliSessionRegistry {

    /** 单会话回放缓冲：256 KB 大约够回放上万行普通输出。 */
    static final int BUFFER_BYTES = 256 * 1024;

    /** 没有浏览器连着时，会话保留多久。 */
    static final long IDLE_MILLIS = 30 * 60 * 1000L;

    /** 连接还在但完全没有心跳 / 输入的时间上限（浏览器异常退出时兜底回收僵尸连接）。 */
    static final long ABANDONED_MILLIS = 2 * 60 * 60 * 1000L;

    /** 每个用户最多保留的会话数。 */
    static final int MAX_SESSIONS_PER_USER = 3;

    private static final long SWEEP_SECONDS = 60;

    private final Map<String, HostCliSession> sessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "host-cli-session-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private HostService hostService;

    @Resource
    private HostAgentService hostAgentService;

    @Resource
    private ContainerService containerService;

    @PostConstruct
    void startSweeper() {
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        sessions.values().forEach(HostCliSession::closeQuietly);
        sessions.clear();
    }

    /** 会话 key：用户 + 主机 + 前端标签页令牌，天然隔离不同用户与不同标签页。 */
    static String key(String userId, String hostId, String token) {
        return userId + ":" + hostId + ":" + token;
    }

    /**
     * 取回或新建会话。已存在且未结束就复用（重连），否则新建 shell。
     *
     * @throws BusinessException 主机不存在、辅助容器不可用或会话数量超限
     */
    public HostCliSession getOrCreate(String hostId, String userId, String token) {
        String key = key(userId, hostId, token);
        HostCliSession existing = sessions.get(key);
        if (existing != null) {
            if (!existing.isClosed()) {
                return existing;
            }
            sessions.remove(key, existing);
            existing.closeQuietly();
        }

        evictForUser(userId);

        HostCliSession created = create(key, hostId, userId);
        HostCliSession raced = sessions.putIfAbsent(key, created);
        if (raced == null) {
            return created;
        }
        if (raced.isClosed()) {
            sessions.replace(key, raced, created);
            return created;
        }
        created.closeQuietly();
        return raced;
    }

    private HostCliSession create(String key, String hostId, String userId) {
        Host host = hostService.findById(hostId).orElseThrow(() -> new BusinessException("主机不存在"));
        HostAgentService.AgentContext context = hostAgentService.requireRunning(host);

        HostCliSession session = new HostCliSession(key, hostId, userId, BUFFER_BYTES);
        ContainerService.ExecSession exec = containerService.openExecSession(host, context.containerId(),
                "/bin/sh",
                session::onOutput,
                () -> onExecComplete(session));
        session.bindExec(exec);
        log.info("主机终端会话已创建 host={} user={} key={}", hostId, userId, key);
        return session;
    }

    /**
     * shell 自己结束（用户 {@code exit}、容器停止）时的回调。
     * <p>
     * 这里运行在 docker-java 的 IO 线程上，不能就地关闭 Docker 客户端，交给回收线程处理。
     */
    private void onExecComplete(HostCliSession session) {
        if (!sessions.remove(session.getKey(), session)) {
            closeLater(session);
            return;
        }
        session.markClosed();
        session.notifyEnded("\r\n[终端会话已结束，正在为你建立新的会话…]\r\n");
        closeLater(session);
        log.info("主机终端会话已结束 host={} user={} key={}",
                session.getHostId(), session.getUserId(), session.getKey());
    }

    /** 主动结束会话（前端点「新建会话」时发送 {@code {"type":"close"}}）。 */
    public void closeSession(HostCliSession session) {
        if (session == null) {
            return;
        }
        if (sessions.remove(session.getKey(), session)) {
            log.info("主机终端会话被用户关闭 host={} user={} key={}",
                    session.getHostId(), session.getUserId(), session.getKey());
        }
        session.markClosed();
        closeLater(session);
    }

    /** 关闭 exec 可能阻塞，统一交给回收线程；容器停止后线程池已关闭时退化为同步关闭。 */
    private void closeLater(HostCliSession session) {
        try {
            sweeper.execute(session::closeQuietly);
        } catch (RejectedExecutionException e) {
            session.closeQuietly();
        }
    }

    private void evictForUser(String userId) {
        List<HostCliSession> mine = sessions.values().stream()
                .filter(session -> userId.equals(session.getUserId()))
                .toList();
        if (mine.size() < MAX_SESSIONS_PER_USER) {
            return;
        }
        HostCliSession victim = mine.stream()
                .filter(session -> !session.isAttached())
                .min(Comparator.comparingLong(HostCliSession::getIdleSince))
                .orElse(null);
        if (victim == null) {
            throw new BusinessException("终端会话数量已达上限（" + MAX_SESSIONS_PER_USER
                    + " 个），请先关闭其他标签页里的终端再试");
        }
        if (sessions.remove(victim.getKey(), victim)) {
            log.info("淘汰旧的主机终端会话 host={} user={} key={}",
                    victim.getHostId(), userId, victim.getKey());
            closeLater(victim);
        }
    }

    private void sweep() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, HostCliSession> entry : sessions.entrySet()) {
                HostCliSession session = entry.getValue();

                if (session.isAbandoned(ABANDONED_MILLIS, now)) {
                    if (sessions.remove(entry.getKey(), session)) {
                        log.info("回收失去心跳的主机终端会话 host={} user={} key={}",
                                session.getHostId(), session.getUserId(), session.getKey());
                        session.notifyEnded("\r\n[终端会话长时间无响应，正在建立新的会话…]\r\n");
                        session.closeQuietly();
                    }
                    continue;
                }

                if (!session.isIdleExpired(IDLE_MILLIS, now)) {
                    continue;
                }
                if (sessions.remove(entry.getKey(), session)) {
                    log.info("回收空闲主机终端会话 host={} user={} key={} 空闲={}秒",
                            session.getHostId(), session.getUserId(), session.getKey(),
                            (now - session.getIdleSince()) / 1000);
                    session.closeQuietly();
                }
            }
        } catch (Exception e) {
            log.warn("回收主机终端会话失败: {}", e.getMessage(), e);
        }
    }
}
