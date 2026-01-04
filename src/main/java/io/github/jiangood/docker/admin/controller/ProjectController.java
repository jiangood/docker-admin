package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.admin.common.dto.AjaxResult;
import io.admin.common.dto.antd.Option;
import io.admin.framework.config.argument.RequestBodyKeys;
import io.admin.framework.config.security.HasPermission;
import io.admin.framework.data.query.JpaQuery;
import io.admin.modules.common.LoginUtils;
import io.admin.modules.system.service.SysOrgService;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.ProjectService;
import jakarta.annotation.Resource;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("admin/project")
public class ProjectController {


    @Resource
    private ProjectService service;

    @Resource
    private BuildLogService logService;

    @Resource
    private SysOrgService sysOrgService;


    @HasPermission("project:view")
    @RequestMapping("page")
    public AjaxResult page(String orgId, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        JpaQuery<Project> q = buildQuery();
        q.searchText(searchText, "name", "remark");


        if (StrUtil.isNotEmpty(orgId)) {
            List<String> orgIds = sysOrgService.findChildIdListById(orgId);
            orgIds.add(orgId);

            q.in(Project.Fields.sysOrg + ".id", orgIds);
        }


        Page<Project> page = this.service.findAll(q, pageable);

        return AjaxResult.ok().data(page);
    }

    private JpaQuery<Project> buildQuery() {
        JpaQuery<Project> q = new JpaQuery<>();
        q.addSubOr(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginUtils.getOrgPermissions());
        });

        return q;
    }

    @HasPermission("project:save")
    @PostMapping({"save"})
    public AjaxResult save(@RequestBody Project param, RequestBodyKeys updateFields) throws Exception {
        if (param.getSysOrg().getId() == null) {
            param.setSysOrg(null);
        }
        param.setGitUrl(param.getGitUrl().trim());
        param.setName(param.getName().trim());
        Project result = this.service.saveOrUpdateByRequest(param, updateFields);
        return AjaxResult.ok().data(result.getId()).msg("保存成功");
    }


    @HasPermission("project:delete")
    @PostMapping({"delete"})
    public AjaxResult delete(String id) {
        this.service.deleteProject(id);
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("project:view")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        Project project = service.findOne(id);
        return AjaxResult.ok().data(project);
    }


    @HasPermission(value = "project:build")
    @RequestMapping("build")
    public AjaxResult build(
            BuildRequest buildRequest,
            @RequestParam String projectId,
            String buildHostId
    ) throws InterruptedException, IOException, GitAPIException {


        Project project = service.findOne(projectId);
        service.checkBuildImage();


        // 更新最近时间,方便排序
        project.setUpdateTime(new Date());
        project = service.save(project);

        buildRequest.setBranchOrTag(project.getBranch());
        buildRequest.setProjectId(project.getId());
        buildRequest.setDockerfile(project.getDockerfile());
        buildRequest.setBuildHostId(buildHostId);
        service.buildImage(buildRequest);


        return AjaxResult.ok().msg("构建已触发");
    }

    @RequestMapping("stopBuild")
    public AjaxResult stopBuild(@RequestParam String id) throws IOException {
        service.stopBuild(id);

        return AjaxResult.ok();
    }

    @RequestMapping("cleanErrorLog")
    public AjaxResult cleanErrorLog(@RequestParam String id) {
        service.cleanErrorLog(id);
        return AjaxResult.ok();
    }


    @RequestMapping("options")
    public List<Option> options() throws InterruptedException, IOException, GitAPIException {
        JpaQuery<Project> q = buildQuery();

        List<Project> list = service.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime"));

        List<Option> options = new ArrayList<>();
        for (Project h : list) {
            options.add(Option.of(h.getId(), h.getName()));
        }


        return options;
    }

    @RequestMapping("versions")
    public List<Option> versions(String projectId) {
        List<String> versions = logService.versions(projectId);


        return versions.stream().map(v -> Option.of(v, v)).toList();
    }
}
