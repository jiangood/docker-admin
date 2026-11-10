package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.service.SystemConfigService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 全局设置接口：默认注册中心与默认构建主机。
 * <p>
 * 邮件发件账号、容器运行 / 主机辅助容器 / 容器日志都已改为框架「系统管理 - 参数设置」维护
 * （{@code admin/sys-config}），这里不再提供对应接口，避免出现第二个配置入口。
 */
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

    @Log("全局设置-保存默认资源")
    @HasPermission("system-config:save")
    @PostMapping("defaults")
    public AjaxResult saveDefaults(@RequestBody DefaultsRequest request) {
        return AjaxResult.ok().data(service.saveDefaults(request.defaultRegistryId(), request.defaultBuildHostId()))
                .msg("保存成功");
    }

    public record DefaultsRequest(String defaultRegistryId, String defaultBuildHostId) {}
}
