package io.github.jiangood.docker.admin.websocket;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.SpringTool;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台任务实时日志 WebSocket（镜像同步、构建、部署等）。
 * <p>
 * 连接后先把日志文件已有内容补齐（避免错过 WS 建立前的日志），再增量 tail，
 * 任务结束并写完最后内容后关闭连接。数据来源即 {@code /data/logs/{logId}.log}。
 */
@Slf4j
@Component
public class SyncLogWebSocketHandler extends TextWebSocketHandler {

    /**
     * 增量读取间隔（毫秒）。
     */
    private static final long POLL_INTERVAL = 300L;

    /**
     * 任务结束后等待最后刷盘的宽限次数。
     */
    private static final int END_GRACE_TIMES = 3;

    /**
     * 单次最多读取的字节数：日志可能很长，分批发给前端，避免一次分配巨大数组。
     */
    private static final int MAX_CHUNK = 512 * 1024;

    @Resource
    private TaskLogRegistry taskLogRegistry;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String logId = StrUtil.subAfter(session.getUri().getPath(), "/", true);
        executorService.submit(() -> streamLog(session, logId));
    }

    private void streamLog(WebSocketSession session, String logId) {
        File file = new File(logPath(), logId + ".log");
        // 已发送到的字节偏移：日志文件会随任务持续增长，只读增量部分，
        // 避免每次轮询整文件重读（大日志下是 O(n²)）。
        long sent = 0;
        int grace = 0;
        try {
            while (session.isOpen()) {
                sent = sendNewContent(session, file, sent);

                if (taskLogRegistry.isRunning(logId)) {
                    grace = 0;
                } else {
                    grace++;
                    if (grace >= END_GRACE_TIMES) {
                        // 任务已结束：把最后没有换行结尾的残余内容也发出去
                        sendTail(session, file, sent);
                        session.sendMessage(new TextMessage("\n[任务已结束]"));
                        break;
                    }
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("同步日志推送终止: {}", e.getMessage());
        } finally {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception e) {
                log.debug("关闭同步日志会话失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 把文件自 {@code from} 之后新增的内容发送出去，只发送到最后一个换行，
     * 避免把未写完的多字节字符截断。
     *
     * @return 新的已发送偏移
     */
    private long sendNewContent(WebSocketSession session, File file, long from) throws Exception {
        if (!file.isFile()) {
            return from;
        }
        long length = file.length();
        if (length < from) {
            // 文件被重建（如同一 logId 复用），从头再发
            from = 0;
        }
        long remaining = length - from;
        if (remaining <= 0) {
            return from;
        }

        int size = (int) Math.min(remaining, MAX_CHUNK);
        byte[] chunk = read(file, from, size);

        int lastNewline = -1;
        for (int i = chunk.length - 1; i >= 0; i--) {
            if (chunk[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        if (lastNewline < 0) {
            // 本次读到的内容还没有换行结尾：整批不足一个缓冲时继续等，凑满则原样发出，
            // 否则超长单行（无换行）会让推送永远卡住
            if (remaining <= MAX_CHUNK) {
                return from;
            }
            int end = utf8Boundary(chunk);
            if (end <= 0) {
                return from;
            }
            session.sendMessage(new TextMessage(new String(chunk, 0, end, StandardCharsets.UTF_8)));
            return from + end;
        }

        session.sendMessage(new TextMessage(new String(chunk, 0, lastNewline + 1, StandardCharsets.UTF_8)));
        return from + lastNewline + 1;
    }

    /**
     * 找出 chunk 中「完整 UTF-8 字符序列」的结束位置，避免把多字节字符截成两半。
     */
    private static int utf8Boundary(byte[] chunk) {
        int i = chunk.length;
        // 跳过尾部续字节（10xxxxxx），让 i 停在最后一个字符的结束位置
        while (i > 0 && (chunk[i - 1] & 0xC0) == 0x80) {
            i--;
        }
        if (i == 0) {
            return chunk.length; // 全是续字节：异常数据，原样发出
        }
        int lead = chunk[i - 1] & 0xFF;
        int need = lead < 0x80 ? 1 : lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        int start = i - 1;
        // 前导字节之后的字节数不足 need 时说明这个字符没写完整，丢弃这几个字节等下次再读
        return chunk.length - start >= need ? chunk.length : start;
    }

    /**
     * 任务结束后补发最后一段没有换行结尾的内容。
     */
    private void sendTail(WebSocketSession session, File file, long from) throws Exception {
        if (!file.isFile()) {
            return;
        }
        long length = file.length();
        while (length > from) {
            int size = (int) Math.min(length - from, MAX_CHUNK);
            byte[] chunk = read(file, from, size);
            if (chunk.length == 0) {
                return;
            }
            // 只有最后一批可以原样解码；中间批次按 UTF-8 字符边界切，避免截断多字节字符
            int end = chunk.length < size ? chunk.length : utf8Boundary(chunk);
            if (end <= 0) {
                return;
            }
            session.sendMessage(new TextMessage(new String(chunk, 0, end, StandardCharsets.UTF_8)));
            from += end;
        }
    }

    private static byte[] read(File file, long from, int size) throws Exception {
        byte[] chunk = new byte[size];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(from);
            int read = raf.read(chunk);
            if (read <= 0) {
                return new byte[0];
            }
            return read == size ? chunk : java.util.Arrays.copyOf(chunk, read);
        }
    }

    private static String logPath() {
        String path = SpringTool.getProperty("logging.file.path");
        return StrUtil.isBlank(path) ? "/data/logs" : path;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

}
