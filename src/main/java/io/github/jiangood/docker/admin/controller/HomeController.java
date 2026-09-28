package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.BuildLogService;
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
