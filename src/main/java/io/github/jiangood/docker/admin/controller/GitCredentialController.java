package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.GitCredential;
import io.github.jiangood.docker.admin.service.GitCredentialService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("admin/git-credential")
public class GitCredentialController {

    @Resource
    private GitCredentialService service;

    @PreAuthorize("hasAuthority('git-credential:view')")
    @RequestMapping("page")
    public AjaxResult page(String searchText,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) {
        Spec<GitCredential> q = Spec.of();
        q.orLike(searchText,
                GitCredential.Fields.name, GitCredential.Fields.url, GitCredential.Fields.username);
        Page<GitCredential> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    @Log("Git凭据-保存")
    @PreAuthorize("hasAuthority('git-credential:save')")
    @PostMapping("save")
    public AjaxResult save(@RequestBody GitCredential input) {
        service.saveCredential(input);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("Git凭据-删除")
    @PreAuthorize("hasAuthority('git-credential:delete')")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteById(id);
        return AjaxResult.ok().msg("删除成功");
    }
}
