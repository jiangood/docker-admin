package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.RegistryService;
import io.github.jiangood.openadmin.framework.config.RequestBodyKeys;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("admin/registry")
public class RegistryController {

    @Resource
    private RegistryService service;

    @HasPermission("registry:view")
    @RequestMapping("info")
    public AjaxResult info() {
        return AjaxResult.ok().data(service.getEffective());
    }

    @Log("镜像注册中心-保存")
    @HasPermission("registry:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody Registry input, RequestBodyKeys updateFields) {
        service.saveRegistry(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }
}
