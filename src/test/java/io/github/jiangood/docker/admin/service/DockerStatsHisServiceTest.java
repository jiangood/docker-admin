package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dto.ContainerUsageVo;
import io.github.jiangood.docker.admin.dto.HostUsageSeriesVo;
import io.github.jiangood.docker.admin.dto.HostUsageVo;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器粒度历史落库与主机趋势聚合验证：累计计数器求速率、计数器归零断点。
 * <p>
 * 用独立的 H2 文件并关掉定时任务，不碰 ./data 下的开发库。
 */
@SpringBootTest(properties = {
        "db_path=./target/test-db/usage",
        "server.port=0",
        "sys.job-enable=false",
        "logging.file.path=./target/test-logs"
})
class DockerStatsHisServiceTest {

    @Resource
    private DockerStatsHisService service;

    @Test
    void 按十分钟采样并按累计值求速率() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusHours(2).withMinute(0).withSecond(0).withNano(0);

        for (int i = 0; i < 5; i++) {
            // 每 10 分钟网络接收增加 600 000 字节 => 1 000 B/s
            service.saveBatch(List.of(hostSample(hostId, base.plusMinutes(i * 10L), 1000L * i * 600, true)));
        }

        HostUsageSeriesVo series = service.series(hostId, "6h");

        assertEquals(5, series.getSampleCount());
        assertEquals(5, series.getPoints().size());
        // 第一个点没有前一个采样，速率无从计算
        assertNull(series.getPoints().get(0).getNetRxBps());
        assertEquals(1000.0, series.getPoints().get(1).getNetRxBps(), 0.5);
        assertEquals(1000.0, series.getPoints().get(4).getNetRxBps(), 0.5);
        // 瞬时值直接透传/聚合
        assertEquals(10.0, series.getPoints().get(0).getCpuPercent(), 0.001);
        assertEquals(1, series.getPoints().get(0).getContainersRunning());
    }

    @Test
    void 计数器归零时速率置空而不是补零() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusHours(2).withMinute(0).withSecond(0).withNano(0);

        service.saveBatch(List.of(hostSample(hostId, base, 6000L, true)));
        service.saveBatch(List.of(hostSample(hostId, base.plusMinutes(10), 7000L, true)));
        // 容器重启：累计值突然变小
        service.saveBatch(List.of(hostSample(hostId, base.plusMinutes(20), 100L, true)));

        List<HostUsageSeriesVo.Point> points = service.series(hostId, "6h").getPoints();

        assertEquals(3, points.size());
        assertNotNull(points.get(1).getNetRxBps());
        assertNull(points.get(2).getNetRxBps(), "计数器归零应断开曲线，而不是给出一个假速率");
    }

    @Test
    void 采集失败的主机不写采样() {
        String hostId = uniqueHost();
        LocalDateTime base = LocalDateTime.now().minusHours(1).withMinute(0).withSecond(0).withNano(0);

        HostUsageVo vo = hostSample(hostId, base, 0L, false);
        service.saveBatch(List.of(vo));

        HostUsageSeriesVo series = service.series(hostId, "6h");
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

    private static HostUsageVo hostSample(String hostId, LocalDateTime at, long netRx, boolean reachable) {
        HostUsageVo vo = new HostUsageVo();
        vo.setHostId(hostId);
        vo.setName("测试主机");
        vo.setReachable(reachable);
        vo.setCpus(4);
        vo.setMemoryTotal(8L * 1024 * 1024 * 1024);
        vo.setCollectedAt(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        // 主机级聚合字段（部分逻辑可能用到），同时提供容器明细供 per-container 历史落库
        vo.setCpuPercent(10.0);
        vo.setCpuCoresUsed(0.4);
        vo.setMemUsed(120L * 1024 * 1024);
        vo.setMemPercent(1.5);
        vo.setNetRx(netRx);
        vo.setNetTx(0L);
        vo.setBlockRead(0L);
        vo.setBlockWrite(0L);
        vo.setContainersRunning(1);

        if (reachable) {
            ContainerUsageVo c = new ContainerUsageVo();
            c.setId("c-" + hostId);
            c.setName("c1");
            c.setImage("nginx:latest");
            c.setCpuPercent(10.0);
            c.setMemUsage(120L * 1024 * 1024);
            c.setMemLimit(8L * 1024 * 1024 * 1024);
            c.setMemPercent(1.5);
            c.setNetRx(netRx);
            c.setNetTx(0L);
            c.setBlockRead(0L);
            c.setBlockWrite(0L);
            c.setPids(1L);
            List<ContainerUsageVo> list = new ArrayList<>();
            list.add(c);
            vo.setContainers(list);
        }
        return vo;
    }
}
