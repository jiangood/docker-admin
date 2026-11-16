package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.HostAgentConfig;
import io.github.jiangood.docker.admin.service.HostAgentConfigService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具菜单「主机 Agent」配置的 CRUD。
 * <p>
 * 只维护配置（主机 / 镜像 tag / 挂载目录），终端与部署仍由 {@link HostAgentController} 提供。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("admin/host-agent-config")
public class HostAgentConfigController {

    private final HostAgentConfigService service;

    @HasPermission("host-agent:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) {
        Spec<HostAgentConfig> q = Spec.of();
        q.orLike(searchText, HostAgentConfig.Fields.name, HostAgentConfig.Fields.remark);
        Page<HostAgentConfig> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    @HasPermission("host-agent:view")
    @RequestMapping("info")
    public AjaxResult info(String id) {
        HostAgentConfig config = service.findById(id)
                .orElseThrow(() -> new BusinessException("Agent 配置不存在"));
        return AjaxResult.ok().data(config);
    }

    @Log("主机 Agent-保存")
    @HasPermission("host-agent:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody HostAgentConfig input,
            @RequestHeader(value = "X-Body-Fields", required = false) List<String> updateFields) {
        service.saveConfig(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("主机 Agent-删除")
    @HasPermission("host-agent:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteConfig(id);
        return AjaxResult.ok().msg("删除成功");
    }

    /**
     * 读取某主机的常用命令（终端页「常用命令」抽屉用）。
     * 命令只在终端场景下使用，权限与 host-cli 保持一致。
     */
    @HasPermission("host:cli")
    @RequestMapping("commands")
    public AjaxResult commands(String hostId) {
        return AjaxResult.ok().data(service.findCommands(hostId));
    }

    /**
     * 整体保存某主机的常用命令：body 是命令数组，hostId 走查询参数
     * （与前端 {@code HttpClient.post(url, body, params)} 对齐）。
     */
    @Log("主机 Agent-常用命令保存")
    @HasPermission("host:cli")
    @PostMapping("commands/save")
    public AjaxResult saveCommands(String hostId,
            @RequestBody List<HostAgentConfig.AgentCommand> commands) {
        List<HostAgentConfig.AgentCommand> saved = service.saveCommands(hostId, commands);
        return AjaxResult.ok().msg("保存成功").data(saved);
    }

}
