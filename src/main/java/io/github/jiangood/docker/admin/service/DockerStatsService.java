package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.DockerStatsRepository;
import io.github.jiangood.docker.admin.dto.ContainerUsageVo;
import io.github.jiangood.docker.admin.entity.DockerStats;
import jakarta.annotation.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 容器用量最新快照（t_docker_stats，按容器粒度，每轮覆盖）：写入与查询。
 */
@Service
public class DockerStatsService {

    @Resource
    private DockerStatsRepository repository;

    /**
     * 用本轮采集结果整体替换该主机的容器快照。
     */
    @Transactional
    public int replaceHost(String hostId, String hostName, Integer hostCpus, Long hostMemoryTotal,
                           Long collectedAt, List<ContainerUsageVo> containers) {
        repository.deleteByHostId(hostId);
        if (containers == null || containers.isEmpty()) {
            return 0;
        }
        LocalDateTime at = toLocalDateTime(collectedAt);
        List<DockerStats> rows = new ArrayList<>(containers.size());
        for (ContainerUsageVo vo : containers) {
            if (vo == null || !StringUtils.hasText(vo.getId())) {
                continue;
            }
            rows.add(toEntity(vo, hostId, hostName, hostCpus, hostMemoryTotal, at));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * 清空该主机的容器快照（主机离线 / 采集失败 / 退避跳过时调用）。
     */
    @Transactional
    public int clearHost(String hostId) {
        if (!StringUtils.hasText(hostId)) {
            return 0;
        }
        return repository.deleteByHostId(hostId);
    }

    public ContainerUsageVo findByAppId(String appId) {
        if (!StringUtils.hasText(appId)) {
            return null;
        }
        DockerStats entity = repository.findByAppId(appId);
        return entity == null ? null : toVo(entity);
    }

    public ContainerUsageVo findByHostIdAndContainerId(String hostId, String containerId) {
        if (!StringUtils.hasText(hostId) || !StringUtils.hasText(containerId)) {
            return null;
        }
        DockerStats entity = repository.findByHostIdAndContainerId(hostId, containerId);
        return entity == null ? null : toVo(entity);
    }

    public List<ContainerUsageVo> findByHostId(String hostId) {
        if (!StringUtils.hasText(hostId)) {
            return Collections.emptyList();
        }
        List<DockerStats> list = repository.findByHostId(hostId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContainerUsageVo> result = new ArrayList<>(list.size());
        for (DockerStats e : list) {
            result.add(toVo(e));
        }
        return result;
    }

    public List<ContainerUsageVo> topByMemUsage(int limit) {
        int n = limit <= 0 ? 5 : Math.min(limit, 100);
        List<DockerStats> list = repository.findTopByMemUsage(PageRequest.of(0, n));
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContainerUsageVo> result = new ArrayList<>(list.size());
        for (DockerStats e : list) {
            result.add(toVo(e));
        }
        return result;
    }

    /**
     * 列出最新快照，可按主机过滤。
     */
    public List<ContainerUsageVo> list(String hostId) {
        List<DockerStats> list = StringUtils.hasText(hostId)
                ? repository.findByHostId(hostId)
                : repository.findAll();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContainerUsageVo> result = new ArrayList<>(list.size());
        for (DockerStats e : list) {
            result.add(toVo(e));
        }
        return result;
    }

    public static ContainerUsageVo toVo(DockerStats e) {
        if (e == null) {
            return null;
        }
        ContainerUsageVo vo = new ContainerUsageVo();
        vo.setId(e.getContainerId());
        vo.setName(e.getContainerName());
        vo.setImage(e.getImage());
        vo.setHostId(e.getHostId());
        vo.setHostName(e.getHostName());
        vo.setAppId(e.getAppId());
        vo.setAppName(e.getAppName());
        vo.setCpuPercent(e.getCpuPercent());
        vo.setMemUsage(e.getMemUsage());
        vo.setMemLimit(e.getMemLimit());
        vo.setMemPercent(e.getMemPercent());
        vo.setNetRx(e.getNetRx());
        vo.setNetTx(e.getNetTx());
        vo.setBlockRead(e.getBlockRead());
        vo.setBlockWrite(e.getBlockWrite());
        vo.setPids(e.getPids());
        vo.setError(e.getError());
        vo.setCollectedAt(e.getCollectedAt() == null ? null
                : e.getCollectedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return vo;
    }

    private static DockerStats toEntity(ContainerUsageVo vo, String hostId, String hostName,
                                        Integer hostCpus, Long hostMemoryTotal, LocalDateTime at) {
        DockerStats e = new DockerStats();
        e.setHostId(hostId);
        e.setHostName(hostName);
        e.setHostCpus(hostCpus);
        e.setHostMemoryTotal(hostMemoryTotal);
        e.setContainerId(vo.getId());
        e.setContainerName(vo.getName());
        e.setImage(vo.getImage());
        e.setAppId(vo.getAppId());
        e.setAppName(vo.getAppName());
        e.setCollectedAt(at);
        e.setCpuPercent(vo.getCpuPercent());
        e.setMemUsage(vo.getMemUsage());
        e.setMemLimit(vo.getMemLimit());
        e.setMemPercent(vo.getMemPercent());
        e.setNetRx(vo.getNetRx());
        e.setNetTx(vo.getNetTx());
        e.setBlockRead(vo.getBlockRead());
        e.setBlockWrite(vo.getBlockWrite());
        e.setPids(vo.getPids());
        e.setError(vo.getError());
        return e;
    }

    public static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
