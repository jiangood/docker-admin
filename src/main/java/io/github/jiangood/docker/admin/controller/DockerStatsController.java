package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.service.DockerStatsHisService;
import io.github.jiangood.docker.admin.service.DockerStatsService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器资源用量数据查看（t_docker_stats / t_docker_stats_his）。
 */
@RestController
@RequestMapping("admin/docker-stats")
public class DockerStatsController {

    @Resource
    private DockerStatsService dockerStatsService;

    @Resource
    private DockerStatsHisService dockerStatsHisService;

    /**
     * 最新快照列表（t_docker_stats），可按主机过滤。
     */
    @HasPermission("docker-stats:view")
    @RequestMapping("latest")
    public AjaxResult latest(String hostId) {
        return AjaxResult.ok().data(dockerStatsService.list(hostId));
    }

    /**
     * 历史趋势（t_docker_stats_his，按主机聚合）。
     */
    @HasPermission("docker-stats:view")
    @RequestMapping("series")
    public AjaxResult series(String hostId, @RequestParam(defaultValue = "24h") String range) {
        return AjaxResult.ok().data(dockerStatsHisService.series(hostId, range));
    }
}
