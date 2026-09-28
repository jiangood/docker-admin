package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.RegistryService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @PreAuthorize("hasAuthority('registry:view')")
    @RequestMapping("info")
    public AjaxResult info() {
        return AjaxResult.ok().data(service.getEffective());
    }

    @Log("镜像注册中心-保存")
    @PreAuthorize("hasAuthority('registry:save')")
    @PostMapping("save")
    public AjaxResult save(@RequestBody Registry input) {
        service.saveRegistry(input);
        return AjaxResult.ok().msg("保存成功");
    }
}
