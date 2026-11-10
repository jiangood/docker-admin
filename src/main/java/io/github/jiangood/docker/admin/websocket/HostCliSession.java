package io.github.jiangood.docker.admin.websocket;

import io.github.jiangood.docker.admin.service.ContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 主机 Docker CLI 的持久会话：docker exec 出来的 shell 不再属于某一条 WebSocket 连接。
 * <p>
 * 浏览器关掉 / 切走 / 网络抖动时只是「取消订阅」，命令继续在辅助容器里跑，输出继续写进
 * {@link ByteRingBuffer}；重新连上同一个 key 时先把缓冲回放一遍，再接着实时转发，
 * 因此长耗时命令不会因为刷新页面或切标签页而中断。
 * <p>
 * 同一时刻只允许一个订阅者（第二个标签页连上来会接管，并把旧的按
 * {@link #TAKEN_OVER_CODE} 关掉，让它别再自动抢回去）。
 */
@Slf4j
public class HostCliSession {

    /** 会话已被其他窗口接管，前端收到这个关闭码后不再自动重连。 */
    static final int TAKEN_OVER_CODE = 4001;

    private final String key;
    private final String hostId;
    private final String userId;
    private final ByteRingBuffer buffer;
    private final Object lock = new Object();

    private ContainerService.ExecSession exec;
    private WebSocketSession ws;
    /** 回放是否完成：未完成时新输出只进缓冲，避免和回放内容乱序。 */
    private boolean ready;
    private long attachMark;
    private int cols = 80;
    private int rows = 24;
    /** 最后收到用户输入 / 心跳的时间。 */
    private long lastActiveAt = System.currentTimeMillis();
    /** 最后一次输出或输入的时间：断开后用它判断是否还在干活，持续输出的长任务不会被回收。 */
    private long lastIoAt = System.currentTimeMillis();
    /** 失去订阅者的时间；0 表示当前有订阅者。 */
    private long detachedAt = System.currentTimeMillis();
    private volatile boolean closed;

    HostCliSession(String key, String hostId, String userId, int bufferBytes) {
        this.key = key;
        this.hostId = hostId;
        this.userId = userId;
        this.buffer = new ByteRingBuffer(bufferBytes);
    }

    void bindExec(ContainerService.ExecSession exec) {
        int c;
        int r;
        synchronized (lock) {
            this.exec = exec;
            c = cols;
            r = rows;
        }
        if (exec != null) {
            exec.resize(c, r);
        }
    }

    /**
     * 订阅者接入：返回需要先回放的输出快照。
     * <p>
     * 若已存在别的订阅者，关闭旧连接（{@link #TAKEN_OVER_CODE}），避免两个窗口互相抢。
     */
    byte[] attach(WebSocketSession socket) {
        WebSocketSession previous;
        byte[] replay;
        synchronized (lock) {
            previous = ws;
            ws = socket;
            ready = false;
            detachedAt = 0;
            lastActiveAt = System.currentTimeMillis();
            attachMark = buffer.appended();
            replay = buffer.snapshot();
        }
        if (previous != null && previous != socket) {
            sendText(previous, "\r\n[该会话已在其他窗口接管，本窗口不再自动重连]\r\n");
            closeWith(previous, TAKEN_OVER_CODE);
        }
        return replay;
    }

    /**
     * 回放完成：补发回放期间新到的字节，再开始实时转发。
     * <p>
     * 补发必须和 {@code ready = true} 在同一个锁里完成，否则新输出会插到回放内容前面。
     */
    void markReady(WebSocketSession socket) {
        synchronized (lock) {
            if (ws != socket) {
                return;
            }
            sendBinaryLocked(buffer.since(attachMark));
            attachMark = buffer.appended();
            ready = true;
        }
    }

    /** docker exec 的输出回调（可能在 docker-java 的 IO 线程上）。 */
    void onOutput(byte[] chunk) {
        synchronized (lock) {
            buffer.append(chunk);
            // 有输出就刷新 I/O 时间：断开后依靠它判断命令是否还在干活（例如 docker pull 进度）
            lastIoAt = System.currentTimeMillis();
            if (ready && ws != null) {
                sendBinaryLocked(chunk);
            }
        }
    }

    void write(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        ContainerService.ExecSession target;
        synchronized (lock) {
            target = exec;
            lastActiveAt = System.currentTimeMillis();
            lastIoAt = lastActiveAt;
        }
        if (target != null) {
            target.write(data);
        }
    }

    void resize(int cols, int rows) {
        if (cols <= 0 || rows <= 0) {
            return;
        }
        ContainerService.ExecSession target;
        synchronized (lock) {
            this.cols = cols;
            this.rows = rows;
            target = exec;
        }
        if (target != null) {
            target.resize(cols, rows);
        }
    }

    /** 心跳：只刷新活跃时间，不产生任何输出。 */
    void touch() {
        synchronized (lock) {
            lastActiveAt = System.currentTimeMillis();
        }
    }

    /** 浏览器断开：只取消订阅，不关 shell。 */
    void detach(WebSocketSession socket) {
        synchronized (lock) {
            if (ws != socket) {
                return;
            }
            ws = null;
            ready = false;
            detachedAt = System.currentTimeMillis();
        }
    }

    boolean isAttached() {
        synchronized (lock) {
            return ws != null;
        }
    }

    boolean isClosed() {
        return closed;
    }

    long getIdleSince() {
        synchronized (lock) {
            return detachedAt;
        }
    }

    long getLastActiveAt() {
        synchronized (lock) {
            return lastActiveAt;
        }
    }

    long getLastIoAt() {
        synchronized (lock) {
            return lastIoAt;
        }
    }

    /** 是否该回收：由 {@link CliSessionRetention} 统一判定（静默窗口 + 断开硬上限）。 */
    boolean isReclaimable(long quietMillis, long maxDetachedMillis, long now) {
        synchronized (lock) {
            return CliSessionRetention.shouldReclaim(ws != null, detachedAt, lastIoAt, now,
                    quietMillis, maxDetachedMillis);
        }
    }

    /**
     * 连接还在、但很久没有心跳 / 输入：多半是浏览器异常退出留下的僵尸连接，
     * 服务端感知不到 TCP 断开时用它兜底回收。
     */
    boolean isAbandoned(long millis, long now) {
        synchronized (lock) {
            return ws != null && now - lastActiveAt >= millis;
        }
    }

    /** shell 已退出：由会话注册表标记，避免重复清理。 */
    void markClosed() {
        closed = true;
    }

    /** 通知当前订阅者「会话结束」并关闭连接；前端会自动重连拿到新 shell。 */
    void notifyEnded(String text) {
        WebSocketSession socket;
        synchronized (lock) {
            socket = ws;
            ws = null;
            ready = false;
        }
        if (socket != null) {
            sendText(socket, text);
            closeWith(socket, CloseStatus.NORMAL.getCode());
        }
    }

    /** 关闭 shell、释放 Docker 客户端。可由任意线程调用。 */
    void closeQuietly() {
        closed = true;
        ContainerService.ExecSession target;
        synchronized (lock) {
            target = exec;
            exec = null;
            ws = null;
            ready = false;
        }
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception e) {
            log.debug("关闭主机终端会话失败: {}", e.getMessage());
        }
    }

    String getKey() {
        return key;
    }

    String getHostId() {
        return hostId;
    }

    String getUserId() {
        return userId;
    }

    private void sendBinaryLocked(byte[] payload) {
        WebSocketSession socket = ws;
        if (socket == null || payload == null || payload.length == 0) {
            return;
        }
        if (!socket.isOpen()) {
            return;
        }
        try {
            synchronized (socket) {
                socket.sendMessage(new BinaryMessage(payload));
            }
        } catch (Exception e) {
            log.debug("下发主机终端输出失败: {}", e.getMessage());
        }
    }

    private static void sendText(WebSocketSession socket, String text) {
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            synchronized (socket) {
                socket.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            log.debug("发送主机终端提示失败: {}", e.getMessage());
        }
    }

    private static void closeWith(WebSocketSession socket, int code) {
        try {
            if (socket.isOpen()) {
                socket.close(new CloseStatus(code, code == TAKEN_OVER_CODE ? "taken over" : "session ended"));
            }
        } catch (Exception e) {
            log.debug("关闭主机终端连接失败: {}", e.getMessage());
        }
    }
}
