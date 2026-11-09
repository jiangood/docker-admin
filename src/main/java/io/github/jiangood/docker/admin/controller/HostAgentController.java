package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostAgentService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
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

    /**
     * 读取辅助容器状态。tag / root 可选：工具菜单里的 Agent 配置用它展示
     * 「按配置期望的 tag / 目录」计算出的需要重建提示；不传则沿用 Agent 配置里的值。
     */
    @HasPermission("host:list")
    @RequestMapping("status")
    public AjaxResult status(String id, String tag, String root) {
        return AjaxResult.ok().data(hostAgentService.status(requireHost(id), tag, root));
    }

    @Log("主机-部署辅助容器")
    @HasPermission("host:save")
    @PostMapping("deploy")
    public AjaxResult deploy(String id, String root, String tag, Boolean recreate) {
        Host host = requireHost(id);
        return AjaxResult.ok().msg("辅助容器已就绪")
                .data(hostAgentService.deploy(host, root, tag, recreate != null && recreate));
    }

    /**
     * 文件面板的「切换目录」：用新目录重建辅助容器，容器成功起来后才保存目录。
     * 属于基础设施变更（会重启容器、断开 CLI），因此同样要求 {@code host:save}。
     */
    @Log("主机-切换文件目录")
    @HasPermission("host:save")
    @PostMapping("root")
    public AjaxResult switchRoot(String id, String root) {
        Host host = requireHost(id);
        Assert.hasText(root, "请填写宿主机目录");
        String normalized = HostRootPolicy.normalizeRoot(root);
        return AjaxResult.ok().msg("文件目录已切换到 " + normalized)
                .data(hostAgentService.deploy(host, normalized, null, false));
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
