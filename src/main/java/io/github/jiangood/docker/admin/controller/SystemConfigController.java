package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.service.SystemConfigService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("admin/system-config")
public class SystemConfigController {

    @Resource
    private SystemConfigService service;

    @HasPermission("system-config:view")
    @GetMapping("defaults")
    public AjaxResult defaults() {
        return AjaxResult.ok().data(service.getDefaults());
    }

    @Log("系统配置-保存默认资源")
    @HasPermission("system-config:save")
    @PostMapping("defaults")
    public AjaxResult saveDefaults(@RequestBody DefaultsRequest request) {
        return AjaxResult.ok().data(service.saveDefaults(request.defaultRegistryId(), request.defaultBuildHostId()))
                .msg("保存成功");
    }

    public record DefaultsRequest(String defaultRegistryId, String defaultBuildHostId) {}
}
