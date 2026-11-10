package io.github.jiangood.docker.admin.websocket;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteRingBufferTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Test
    void keepsOnlyRecentBytes() {
        ByteRingBuffer buffer = new ByteRingBuffer(1024);
        byte[] chunk = new byte[1024];
        Arrays.fill(chunk, (byte) 'a');
        buffer.append(chunk);
        assertEquals(1024, buffer.size());

        buffer.append(bytes("tail"));
        assertEquals(1024, buffer.size());
        assertTrue(text(buffer.snapshot()).endsWith("tail"));
    }

    @Test
    void oversizedChunkKeepsTail() {
        ByteRingBuffer buffer = new ByteRingBuffer(1024);
        byte[] big = new byte[4096];
        Arrays.fill(big, (byte) 'x');
        System.arraycopy(bytes("END"), 0, big, big.length - 3, 3);
        buffer.append(big);

        assertEquals(1024, buffer.size());
        assertTrue(text(buffer.snapshot()).endsWith("END"));
        assertEquals(4096, buffer.appended());
    }

    @Test
    void danglingLineIsNotReproduced() {
        // 容量 32，写入 28 字节的 4 行 + 6 字节新行，触发裁剪
        ByteRingBuffer buffer = new ByteRingBuffer(32);
        buffer.append(bytes("line-1\nline-2\nline-3\nline-4\n"));
        buffer.append(bytes("XXXXX\n"));

        String replay = text(buffer.snapshot());
        assertEquals("line-2\nline-3\nline-4\nXXXXX\n", replay);
        assertTrue(replay.startsWith("line-2"));
    }

    @Test
    void sinceReturnsOnlyNewBytes() {
        ByteRingBuffer buffer = new ByteRingBuffer(64);
        buffer.append(bytes("hello"));
        long mark = buffer.appended();
        buffer.append(bytes(" world"));

        assertEquals(" world", text(buffer.since(mark)));
        assertEquals("", text(buffer.since(buffer.appended())));
    }

    @Test
    void sinceIgnoresTrimmedBytes() {
        ByteRingBuffer buffer = new ByteRingBuffer(16);
        buffer.append(bytes("0123456789"));
        long mark = buffer.appended();
        buffer.append(bytes("abcdefghij"));

        assertEquals(16, buffer.size());
        // 前面的 "0123456789" 已被裁掉，标记之后的 10 个字节仍然完整
        assertEquals("abcdefghij", text(buffer.since(mark)));
    }

    @Test
    void sinceWithTooOldMarkReturnsWholeBuffer() {
        ByteRingBuffer buffer = new ByteRingBuffer(8);
        buffer.append(bytes("1234567890"));

        assertEquals("34567890", text(buffer.since(0)));
    }
}
