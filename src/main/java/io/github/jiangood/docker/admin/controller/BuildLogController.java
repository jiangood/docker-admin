package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.ProjectService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@Slf4j
@RequestMapping(value = "admin/buildLog")
public class BuildLogController {

    @Resource
    private BuildLogService service;

    @Resource
    private ProjectService projectService;

    @HasPermission("project:view")
    @RequestMapping("list")
    public AjaxResult list(String projectId, @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        Spec<BuildLog> q = Spec.of();
        if (StrUtil.isNotBlank(projectId)) {
            // 指定项目时必须校验该项目的数据权限
            Project project = projectService.findById(projectId).orElse(null);
            Assert.notNull(project, "项目不存在");
            OrgAccessTool.assertAccess(project.getSysOrg());
            q.eq("projectId", projectId);
        } else {
            // 未指定项目时限制在当前用户可访问的项目范围内
            List<String> projectIds = accessibleProjectIds();
            if (projectIds.isEmpty()) {
                // 无可访问项目时构造一个永假条件，避免空 IN 查询
                q.eq("projectId", "__no_access__");
            } else {
                q.in("projectId", projectIds);
            }
        }
        Page<BuildLog> page = service.findAll(q, pageable);


        for (BuildLog log : page) {
            log.setLogUrl(LogUrlTool.getLogViewUrl(log.getId()));
            if (log.getTimeSpend() == null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), LocalDateTime.now()).toMillis());
            }
        }

        return AjaxResult.ok().data( page);
    }

    /**
     * 当前用户可访问的项目 id 列表（管理员返回全部）。
     */
    private List<String> accessibleProjectIds() {
        Spec<Project> q = Spec.of();
        if (!LoginTool.isAdmin()) {
            q.or(qq -> {
                qq.isNull("sysOrg.id");
                qq.in("sysOrg.id", LoginTool.getOrgPermissions());
            });
        }
        return projectService.findAll(q, Sort.unsorted()).stream().map(Project::getId).toList();
    }

}
