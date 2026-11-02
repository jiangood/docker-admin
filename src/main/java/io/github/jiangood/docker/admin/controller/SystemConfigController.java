package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.mail.EmailSettingsInput;
import io.github.jiangood.docker.admin.service.MailService;
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

    @Resource
    private MailService mailService;

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
    @GetMapping("email")
    public AjaxResult email() {
        return AjaxResult.ok().data(service.getEmailSettings());
    }

    @Log("全局设置-保存邮件发送服务")
    @HasPermission("system-config:save")
    @PostMapping("email")
    public AjaxResult saveEmail(@RequestBody EmailSettingsInput request) {
        service.saveEmailSettings(request);
        return AjaxResult.ok().data(service.getEmailSettings()).msg("保存成功");
    }

    @Log("全局设置-发送测试邮件")
    @HasPermission("system-config:save")
    @PostMapping("email/test")
    public AjaxResult testEmail() {
        mailService.sendTest();
        return AjaxResult.ok().msg("测试邮件已发送到发件邮箱，请查收");
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
