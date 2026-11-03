package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.service.SystemConfigService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 全局设置接口：默认资源与主机辅助容器镜像。
 * <p>
 * 邮件发件账号已改为框架「参数设置 - 邮件发送」维护（{@code admin/sys-config} + {@code admin/mail/test}）。
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

    @HasPermission("system-config:view")
    @GetMapping("agent")
    public AjaxResult agent() {
        return AjaxResult.ok().data(service.getAgentSettings());
    }

    @Log("全局设置-保存辅助容器镜像")
    @HasPermission("system-config:save")
    @PostMapping("agent")
    public AjaxResult saveAgent(@RequestBody AgentSettingsInput request) {
        service.saveAgentSettings(request == null ? null : request.repository());
        return AjaxResult.ok().data(service.getAgentSettings()).msg("保存成功");
    }

    public record DefaultsRequest(String defaultRegistryId, String defaultBuildHostId) {}

    public record AgentSettingsInput(String repository) {
    }
}
