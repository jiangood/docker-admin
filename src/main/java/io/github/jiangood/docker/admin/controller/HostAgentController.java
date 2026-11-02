package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostAgentService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主机辅助容器（文件管理 / Docker CLI）的部署与状态。
 * <p>
 * 部署/删除属于基础设施变更，要求 {@code host:save}；查看状态与主机详情一致用 {@code host:list}。
 */
@RestController
@RequestMapping("admin/host/agent")
public class HostAgentController {

    @Resource
    private HostService hostService;

    @Resource
    private HostAgentService hostAgentService;

    @HasPermission("host:list")
    @RequestMapping("status")
    public AjaxResult status(String id) {
        return AjaxResult.ok().data(hostAgentService.status(requireHost(id)));
    }

    @Log("主机-部署辅助容器")
    @HasPermission("host:save")
    @PostMapping("deploy")
    public AjaxResult deploy(String id, String root, String tag, Boolean recreate) {
        Host host = requireHost(id);
        return AjaxResult.ok().msg("辅助容器已就绪")
                .data(hostAgentService.deploy(host, root, tag, recreate != null && recreate));
    }

    @Log("主机-删除辅助容器")
    @HasPermission("host:save")
    @PostMapping("remove")
    public AjaxResult remove(String id) {
        hostAgentService.remove(requireHost(id));
        return AjaxResult.ok().msg("辅助容器已删除");
    }

    private Host requireHost(String id) {
        Assert.hasText(id, "id 不能为空");
        Host host = hostService.findById(id).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }
}
