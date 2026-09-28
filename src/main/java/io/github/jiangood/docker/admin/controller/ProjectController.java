package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.ProjectService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.config.RequestBodyKeys;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("admin/project")
public class ProjectController {
    @Resource
    private ProjectService service;

    @Resource
    private BuildLogService logService;


    @HasPermission("project:view")
    @RequestMapping("page")
    public AjaxResult page(String orgId, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        Spec<Project> q = buildQuery();
        q.orLike(searchText, "name", "cnName", "remark");


        if (StrUtil.isNotEmpty(orgId)) {
            q.eq("sysOrg.id", orgId);
        }


        Page<Project> page = this.service.findAll(q, pageable);

        return AjaxResult.ok().data(page);
    }

    private Spec<Project> buildQuery() {
        Spec<Project> q = Spec.of();
        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        return q;
    }

    @HasPermission("project:save")
    @PostMapping({"save"})
    public AjaxResult save(@RequestBody Project param, RequestBodyKeys updateFields) throws Exception {
        // 修改已有项目时校验数据权限（新增不受限）
        if (StrUtil.isNotBlank(param.getId())) {
            assertProjectAccess(param.getId());
        }
        if (param.getSysOrg() == null || param.getSysOrg().getId() == null) {
            param.setSysOrg(null);
        }
        param.setGitUrl(param.getGitUrl().trim());
        param.setName(param.getName().trim());
        Project result = this.service.update(param, updateFields);
        return AjaxResult.ok().data(result.getId()).msg("保存成功");
    }


    @HasPermission("project:delete")
    @PostMapping({"delete"})
    public AjaxResult delete(String id) {
        assertProjectAccess(id);
        this.service.deleteProject(id);
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("project:view")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        Project project = assertProjectAccess(id);
        return AjaxResult.ok().data(project);
    }

    /**
     * 按 id 读取项目并校验组织数据权限，无权限时抛业务异常。
     */
    private Project assertProjectAccess(String id) {
        Project project = service.findById(id).orElse(null);
        Assert.notNull(project, "项目不存在");
        OrgAccessTool.assertAccess(project.getSysOrg());
        return project;
    }


    @HasPermission("project:build")
    @RequestMapping("build")
    public AjaxResult build(BuildRequest buildRequest, @RequestParam String projectId, String buildHostId) throws IOException {
        Project project = assertProjectAccess(projectId);
        service.checkBuildImage();
        Assert.isTrue(ProjectService.isValidTag(buildRequest.getTag()), "tag 格式不正确，需形如 v1.0.1");

        // 更新最近时间,方便排序
        project.setUpdateTime(LocalDateTime.now());
        project = service.save(project);

        buildRequest.setProjectId(project.getId());
        buildRequest.setDockerfile(project.getDockerfile());
        buildRequest.setBuildHostId(buildHostId);
        service.buildImage(buildRequest);

        return AjaxResult.ok().msg("构建已触发");
    }

    /**
     * 远程 tag 列表（只保留 vX.Y.Z 形式的版本 tag）。
     */
    @HasPermission("project:view")
    @RequestMapping("tags")
    public AjaxResult tags(String projectId) {
        Project project = assertProjectAccess(projectId);
        List<Option> options = service.listRemoteTags(project).stream()
                .filter(ProjectService::isValidTag)
                .map(t -> new Option(t, t))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 重置项目的 webhook token。
     */
    @HasPermission("project:webhook")
    @RequestMapping("resetWebhook")
    public AjaxResult resetWebhook(String id) {
        Project project = assertProjectAccess(id);
        project.setWebhookToken(RandomUtil.randomString(32));
        service.save(project);
        return AjaxResult.ok().msg("已重置").data(project.getWebhookToken());
    }

    @HasPermission("project:build")
    @RequestMapping("stopBuild")
    public AjaxResult stopBuild(@RequestParam String id) throws IOException {
        BuildLog buildLog = logService.findById(id).orElse(null);
        Assert.notNull(buildLog, "构建记录不存在");
        assertProjectAccess(buildLog.getProjectId());
        service.stopBuild(id);

        return AjaxResult.ok();
    }

    @HasPermission("project:build")
    @RequestMapping("cleanErrorLog")
    public AjaxResult cleanErrorLog(@RequestParam String id) {
        assertProjectAccess(id);
        service.cleanErrorLog(id);
        return AjaxResult.ok();
    }


    @HasPermission("project:view")
    @RequestMapping("options")
    public AjaxResult options() {
        Spec<Project> q = buildQuery();

        List<Project> list = service.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime"));

        List<Option> options = new ArrayList<>();
        for (Project h : list) {
            options.add(new Option(h.getId(), h.getName()));
        }


        return AjaxResult.ok().data(options);
    }

    @HasPermission("project:view")
    @RequestMapping("versions")
    public List<Option> versions(String projectId) {
        assertProjectAccess(projectId);
        List<String> versions = logService.versions(projectId);

        return versions.stream().map(v -> new Option(v, v)).toList();
    }
}
