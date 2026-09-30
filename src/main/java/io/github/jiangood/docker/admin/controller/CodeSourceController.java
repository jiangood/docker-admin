package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.service.CodeSourceApiService;
import io.github.jiangood.docker.admin.service.CodeSourceService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Slf4j
@RequestMapping("admin/code-source")
public class CodeSourceController {

    @Resource
    private CodeSourceService service;

    @Resource
    private CodeSourceApiService apiService;

    @HasPermission("code-source:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) {
        Spec<CodeSource> q = Spec.of();
        q.orLike(searchText,
                CodeSource.Fields.name, CodeSource.Fields.url, CodeSource.Fields.username);
        Page<CodeSource> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    /**
     * 供选择器使用的代码源下拉项（不含密码）。不校验权限，仅要求登录。
     */
    @RequestMapping("options")
    public AjaxResult options() {
        return AjaxResult.ok().data(service.options());
    }

    /**
     * 列出代码源上的仓库（目前支持 GitLab），供「新建镜像」「构建测试」时选择。不校验权限，仅要求登录。
     */
    @RequestMapping("projects")
    public AjaxResult projects(String codeSourceId, String search,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Map<String, Object>> page = apiService.listProjects(codeSourceId, search, pageable);
        return AjaxResult.ok().data(page);
    }

    @Log("代码源-保存")
    @HasPermission("code-source:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody CodeSource input) {
        service.saveCredential(input);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("代码源-删除")
    @HasPermission("code-source:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteById(id);
        return AjaxResult.ok().msg("删除成功");
    }
}
