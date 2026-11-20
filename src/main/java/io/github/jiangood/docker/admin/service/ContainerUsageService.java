package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.ContainerUsageRepository;
import io.github.jiangood.docker.admin.dto.ContainerUsageVo;
import io.github.jiangood.docker.admin.entity.ContainerUsage;
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
 * 容器用量最新快照：按主机整体替换、清空与查询。
 * <p>
 * 数据由 {@link HostUsageCollectJob} 每轮采集后写入，页面只读库。
 */
@Service
public class ContainerUsageService {

    @Resource
    private ContainerUsageRepository repository;

    /**
     * 用本轮采集结果整体替换该主机的容器快照（停止/删除/重建的容器随之消失）。
     */
    @Transactional
    public int replaceHost(String hostId, String hostName, Long collectedAt, List<ContainerUsageVo> containers) {
        repository.deleteByHostId(hostId);
        if (containers == null || containers.isEmpty()) {
            return 0;
        }
        LocalDateTime at = toLocalDateTime(collectedAt);
        List<ContainerUsage> rows = new ArrayList<>(containers.size());
        for (ContainerUsageVo vo : containers) {
            if (vo == null || !StringUtils.hasText(vo.getId())) {
                continue;
            }
            rows.add(toEntity(vo, hostId, hostName, at));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * 清空该主机的容器快照（主机离线 / 采集失败 / 退避跳过时调用，避免展示过期数据）。
     */
    @Transactional
    public int clearHost(String hostId) {
        if (!StringUtils.hasText(hostId)) {
            return 0;
        }
        return repository.deleteByHostId(hostId);
    }

    /**
     * 按应用查询当前容器用量。
     */
    public ContainerUsageVo findByAppId(String appId) {
        if (!StringUtils.hasText(appId)) {
            return null;
        }
        ContainerUsage entity = repository.findByAppId(appId);
        return entity == null ? null : toVo(entity);
    }

    /**
     * 按主机 + 容器查询当前用量。
     */
    public ContainerUsageVo findByHostIdAndContainerId(String hostId, String containerId) {
        if (!StringUtils.hasText(hostId) || !StringUtils.hasText(containerId)) {
            return null;
        }
        ContainerUsage entity = repository.findByHostIdAndContainerId(hostId, containerId);
        return entity == null ? null : toVo(entity);
    }

    /**
     * 查询某主机的全部容器用量快照。
     */
    public List<ContainerUsageVo> findByHostId(String hostId) {
        if (!StringUtils.hasText(hostId)) {
            return Collections.emptyList();
        }
        List<ContainerUsage> list = repository.findByHostId(hostId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContainerUsageVo> result = new ArrayList<>(list.size());
        for (ContainerUsage e : list) {
            result.add(toVo(e));
        }
        return result;
    }

    /**
     * 全局按内存用量倒序取 TopN。
     */
    public List<ContainerUsageVo> topByMemUsage(int limit) {
        int n = limit <= 0 ? 5 : Math.min(limit, 100);
        List<ContainerUsage> list = repository.findTopByMemUsage(PageRequest.of(0, n));
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContainerUsageVo> result = new ArrayList<>(list.size());
        for (ContainerUsage e : list) {
            result.add(toVo(e));
        }
        return result;
    }

    public static ContainerUsageVo toVo(ContainerUsage e) {
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

    private static ContainerUsage toEntity(ContainerUsageVo vo, String hostId, String hostName, LocalDateTime at) {
        ContainerUsage e = new ContainerUsage();
        e.setHostId(hostId);
        e.setHostName(hostName);
        e.setContainerId(vo.getId());
        e.setContainerName(vo.getName());
        e.setImage(vo.getImage());
        e.setAppId(vo.getAppId());
        e.setAppName(vo.getAppName());
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
        e.setCollectedAt(at);
        return e;
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
