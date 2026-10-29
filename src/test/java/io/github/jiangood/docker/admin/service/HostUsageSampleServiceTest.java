package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dto.HostUsageSeriesVo;
import io.github.jiangood.docker.admin.dto.HostUsageVo;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量采样落库与历史曲线的口径验证：分桶、累计计数器求速率、计数器归零断点。
 * <p>
 * 用独立的 H2 文件（{@code target/test-db/usage}）并关掉定时任务，不碰 ./data 下的开发库。
 */
@SpringBootTest(properties = {
        "db_path=./target/test-db/usage",
        "server.port=0",
        "sys.job-enable=false",
        "logging.file.path=./target/test-logs"
})
class HostUsageSampleServiceTest {

    @Resource
    private HostUsageSampleService service;

    @Test
    void 每分钟一条采样并按累计值求速率() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusMinutes(20).withSecond(0).withNano(0);

        for (int i = 0; i < 5; i++) {
            // 每分钟网络接收增加 60 000 字节 => 1 000 B/s
            service.saveBatch(List.of(sample(hostId, base.plusMinutes(i), 1000L * i * 60, true)));
        }

        HostUsageSeriesVo series = service.series(hostId, "1h");

        assertEquals(5, series.getSampleCount());
        assertEquals(5, series.getPoints().size());
        // 第一个点没有前一个采样，速率无从计算，留空
        assertNull(series.getPoints().get(0).getNetRxBps());
        assertEquals(1000.0, series.getPoints().get(1).getNetRxBps(), 0.001);
        assertEquals(1000.0, series.getPoints().get(4).getNetRxBps(), 0.001);
        // 瞬时值直接透传
        assertEquals(10.0, series.getPoints().get(0).getCpuPercent(), 0.001);
        assertEquals(3, series.getPoints().get(0).getContainersRunning());
    }

    @Test
    void 计数器归零时速率置空而不是补零() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusMinutes(20).withSecond(0).withNano(0);

        service.saveBatch(List.of(sample(hostId, base, 6000L, true)));
        service.saveBatch(List.of(sample(hostId, base.plusMinutes(1), 7000L, true)));
        // 容器重启：累计值突然变小
        service.saveBatch(List.of(sample(hostId, base.plusMinutes(2), 100L, true)));

        List<HostUsageSeriesVo.Point> points = service.series(hostId, "1h").getPoints();

        assertEquals(3, points.size());
        assertNotNull(points.get(1).getNetRxBps());
        assertNull(points.get(2).getNetRxBps(), "计数器归零应断开曲线，而不是给出一个假速率");
    }

    @Test
    void 采集失败的主机不写采样() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusMinutes(10).withSecond(0).withNano(0);

        service.saveBatch(List.of(sample(hostId, base, 0L, false)));

        HostUsageSeriesVo series = service.series(hostId, "1h");
        assertEquals(0, series.getSampleCount());
        assertTrue(series.getPoints().isEmpty());
    }

    @Test
    void 区间越界没有数据但也不报错() {
        HostUsageSeriesVo series = service.series(uniqueHost(), "7d");
        assertEquals(0, series.getSampleCount());
        assertTrue(series.getPoints().isEmpty());
        assertFalse(series.getBucketMillis() <= 0);
    }

    private static String uniqueHost() {
        return "test-" + UUID.randomUUID();
    }

    private static HostUsageVo sample(String hostId, LocalDateTime at, long netRx, boolean reachable) {
        HostUsageVo vo = new HostUsageVo();
        vo.setHostId(hostId);
        vo.setName("测试主机");
        vo.setReachable(reachable);
        vo.setCpuPercent(10.0);
        vo.setCpuCoresUsed(0.4);
        vo.setMemUsed(120L * 1024 * 1024);
        vo.setMemoryTotal(8L * 1024 * 1024 * 1024);
        vo.setMemPercent(1.5);
        vo.setNetRx(netRx);
        vo.setNetTx(0L);
        vo.setBlockRead(0L);
        vo.setBlockWrite(0L);
        vo.setContainersRunning(3);
        vo.setCollectedAt(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return vo;
    }
}
