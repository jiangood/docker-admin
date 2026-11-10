package io.github.jiangood.docker.admin.websocket;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSessionRetentionTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long QUIET = Duration.ofMinutes(30).toMillis();
    private static final long MAX_DETACHED = Duration.ofHours(24).toMillis();

    private static boolean reclaim(boolean attached, long detachedMinutesAgo, long lastIoMinutesAgo) {
        long detachedAt = detachedMinutesAgo <= 0 ? 0 : NOW - Duration.ofMinutes(detachedMinutesAgo).toMillis();
        long lastIoAt = NOW - Duration.ofMinutes(lastIoMinutesAgo).toMillis();
        return CliSessionRetention.shouldReclaim(attached, detachedAt, lastIoAt, NOW, QUIET, MAX_DETACHED);
    }

    @Test
    void attachedSessionIsNeverReclaimed() {
        assertFalse(reclaim(true, 0, 0));
        assertFalse(reclaim(true, 600, 600));
    }

    @Test
    void sessionWithoutDetachInfoIsNotReclaimed() {
        assertFalse(CliSessionRetention.shouldReclaim(false, 0, NOW, NOW, QUIET, MAX_DETACHED));
    }

    @Test
    void ongoingOutputKeepsSessionAlive() {
        // 断开 40 分钟，但 5 分钟前还有输出（例如 docker pull 仍在刷进度）
        assertFalse(reclaim(false, 40, 5));
        // 断开 23 小时，输出一直没停
        assertFalse(reclaim(false, 23 * 60, 1));
    }

    @Test
    void quietSessionIsReclaimed() {
        // 断开 40 分钟，最后一次输出在 31 分钟前
        assertTrue(reclaim(false, 40, 31));
    }

    @Test
    void lastOutputBeforeDetachStillCountsFromDetach() {
        // 断开前就没输出了：从断开时刻开始计时
        assertFalse(reclaim(false, 29, 120));
        assertTrue(reclaim(false, 31, 120));
    }

    @Test
    void exactlyAtQuietWindowIsReclaimed() {
        assertTrue(reclaim(false, 30, 30));
        assertFalse(reclaim(false, 29, 29));
    }

    @Test
    void maxDetachedCapWinsOverOngoingOutput() {
        // 断开超过 24 小时，即使 1 分钟前还有输出也要回收（tail -f 之类的兜底）
        assertTrue(reclaim(false, 24 * 60, 1));
        assertTrue(reclaim(false, 25 * 60, 1));
        assertFalse(reclaim(false, 24 * 60 - 1, 1));
    }
}
