package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.HostSummaryService;
import io.github.jiangood.docker.admin.service.HostUsageService;
import io.github.jiangood.docker.admin.service.ProjectService;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("admin/home")
public class HomeController {

    @Resource
    BuildLogService buildLogService;

    @Resource
    ProjectService projectService;

    @Resource
    HostSummaryService hostSummaryService;

    @Resource
    HostUsageService hostUsageService;

    /**
     * 首页「主机资源汇总」看板。参考 Rancher 首页：全局概览 + 逐主机明细。
     * <p>
     * 需要 {@code host:list}：没有主机查看权限的用户首页不展示该看板，前端据此隐藏。
     *
     * @param refresh 为 true 时忽略 30 秒缓存强制重新汇总
     */
    @HasPermission("host:list")
    @RequestMapping("hostSummary")
    public AjaxResult hostSummary(@RequestParam(defaultValue = "false") boolean refresh) {
        return AjaxResult.ok().data(hostSummaryService.summary(refresh));
    }

    /**
     * 首页「主机实时用量」：等价于 {@code docker stats} 的主机级汇总（CPU / 内存使用率、容器合计）。
     * <p>
     * 与 {@link #hostSummary} 刻意分开：静态容量先出（快），实时用量后到（每台主机、每个运行中容器
     * 各一次 {@code /stats}，单轮以秒计）。采集慢或失败都不影响首屏，前端只把用量列显示成占位。
     * <p>
     * 默认返回定时任务（每分钟一次）写下的内存快照，<b>不触发采集</b>，所以多标签页、多人同时看
     * 都不会增加主机侧负载；只有 {@code refresh=true}（用户点刷新）才现采一轮。
     *
     * @param refresh 为 true 时忽略缓存立即采集
     */
    @HasPermission("host:list")
    @RequestMapping("hostUsage")
    public AjaxResult hostUsage(@RequestParam(defaultValue = "false") boolean refresh) {
        return AjaxResult.ok().data(refresh
                ? hostUsageService.usageAll(true)
                : hostUsageService.snapshotAll());
    }

    @HasPermission("app:view")
    @RequestMapping("buildingPage")
    public AjaxResult buildingPage(@PageableDefault(direction = Sort.Direction.DESC,sort = "createTime") Pageable pageable) {
        Spec<BuildLog> q = Spec.of();
        q.isNull(BuildLog.Fields.success);

        // 非管理员只能看到自己可访问项目的构建记录
        if (!LoginTool.isAdmin()) {
            Spec<Project> pq = Spec.of();
            pq.or(qq -> {
                qq.isNull("sysOrg.id");
                qq.in("sysOrg.id", LoginTool.getOrgPermissions());
            });
            List<String> projectIds = projectService.findAll(pq, Sort.unsorted()).stream().map(Project::getId).toList();
            if (projectIds.isEmpty()) {
                // 无可访问项目时构造一个永假条件，避免空 IN 查询
                q.eq("projectId", "__no_access__");
            } else {
                q.in("projectId", projectIds);
            }
        }

        Page<BuildLog> page = buildLogService.findAll(q,pageable);

        for (BuildLog log : page) {
            log.setLogUrl(LogUrlTool.getLogViewUrl(log.getId()));
            if (log.getTimeSpend() == null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), LocalDateTime.now()).toMillis());
            }
        }


        return AjaxResult.ok().data(page);

    }
}
