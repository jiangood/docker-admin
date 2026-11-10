package io.github.jiangood.docker.admin.websocket;

/**
 * 主机终端会话的回收判定（纯函数，便于单测）。
 * <p>
 * 规则：只有「已断开」的会话才可能被回收，且满足任意一条即回收：
 * <ol>
 *     <li><b>静默超时</b>：距离最后一次输出 / 输入超过保留窗口。
 *     基准取「断开时刻」和「最后一次 I/O 时刻」中更晚的那个——因此像
 *     {@code docker pull} 这种持续刷进度的长任务不会因为浏览器关掉而被杀；</li>
 *     <li><b>断开超时</b>：距离断开时刻超过硬上限，不管有没有输出，
 *     避免 {@code tail -f} 这类会话无限占用 docker exec 与内存。</li>
 * </ol>
 * 仍然连着的会话永远不回收。
 */
final class CliSessionRetention {

    private CliSessionRetention() {
    }

    /**
     * @param attached          当前是否有 WebSocket 订阅者
     * @param detachedAt        失去订阅者的时刻，0 表示从未断开 / 没有订阅者信息
     * @param lastIoAt          最后一次输出或输入的时刻
     * @param now               当前时刻
     * @param quietMillis       静默保留窗口
     * @param maxDetachedMillis 断开硬上限
     */
    static boolean shouldReclaim(boolean attached, long detachedAt, long lastIoAt,
                                 long now, long quietMillis, long maxDetachedMillis) {
        if (attached || detachedAt <= 0) {
            return false;
        }
        long quietBase = Math.max(detachedAt, lastIoAt);
        boolean quietExpired = now - quietBase >= quietMillis;
        boolean detachedExpired = now - detachedAt >= maxDetachedMillis;
        return quietExpired || detachedExpired;
    }
}
