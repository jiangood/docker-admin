package io.github.jiangood.docker.admin.websocket;

import java.util.Arrays;

/**
 * 固定容量的字节滚动缓冲：只保留最近写入的终端输出，用于断线重连时回放。
 * <p>
 * 容量写满后从头部丢弃，丢弃位置会尽量对齐到换行之后，避免回放时从半行或半个
 * ANSI 转义序列中间开始；但一屏内一直没有换行时按理论值硬丢，不会把缓冲清空。
 * <p>
 * 同时维护「累计写入字节数」{@link #appended()}，订阅者接管时先记下标记，
 * 回放完成后再用 {@link #since(long)} 补齐回放期间新增的字节，保证不乱序、不丢。
 */
final class ByteRingBuffer {

    private static final int MIN_CAPACITY = 4;
    private static final int INITIAL_CAPACITY = 8192;
    /** 为了对齐换行，最多比理论丢弃量多丢这么多字节。 */
    private static final int MAX_LINE_ALIGN = 4096;
    private static final byte[] EMPTY = new byte[0];

    private final int capacity;
    private byte[] data;
    private int size;
    /** 累计写入字节数（绝对偏移，不受裁剪影响）。 */
    private long appended;

    ByteRingBuffer(int capacity) {
        this.capacity = Math.max(MIN_CAPACITY, capacity);
        this.data = new byte[Math.min(this.capacity, INITIAL_CAPACITY)];
    }

    synchronized long appended() {
        return appended;
    }

    synchronized int size() {
        return size;
    }

    synchronized void append(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        appended += chunk.length;

        if (chunk.length >= capacity) {
            ensure(capacity);
            System.arraycopy(chunk, chunk.length - capacity, data, 0, capacity);
            size = capacity;
            return;
        }

        if (size + chunk.length > capacity) {
            int drop = size + chunk.length - capacity;
            int limit = Math.min(size, drop + MAX_LINE_ALIGN);
            int cut = drop;
            while (cut < limit && data[cut] != '\n') {
                cut++;
            }
            if (cut < limit) {
                // 连同换行一起丢掉，回放从下一行开始
                cut++;
            } else {
                // 一直没换行（例如进度条），按理论值丢
                cut = drop;
            }
            System.arraycopy(data, cut, data, 0, size - cut);
            size -= cut;
        }

        ensure(size + chunk.length);
        System.arraycopy(chunk, 0, data, size, chunk.length);
        size += chunk.length;
    }

    synchronized byte[] snapshot() {
        return size == 0 ? EMPTY : Arrays.copyOf(data, size);
    }

    /**
     * 自 {@code mark}（{@link #appended()} 的旧值）之后写入的字节；已被裁剪的部分自动丢弃。
     */
    synchronized byte[] since(long mark) {
        long start = appended - size;
        long from = Math.max(mark, start);
        if (from >= appended) {
            return EMPTY;
        }
        return Arrays.copyOfRange(data, (int) (from - start), size);
    }

    private void ensure(int needed) {
        if (needed <= data.length) {
            return;
        }
        int target = Math.min(capacity, Math.max(needed, Math.max(INITIAL_CAPACITY, data.length * 2)));
        data = Arrays.copyOf(data, target);
    }
}
