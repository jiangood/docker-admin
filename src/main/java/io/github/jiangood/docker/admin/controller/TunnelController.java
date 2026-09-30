package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.TunnelNode;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.docker.admin.service.TunnelService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 隧道（frp）：平台维护连接配置与隧道列表，并通过 docker-java 部署各节点的 frpc 容器。
 * <p>
 * frps 服务端不由平台下发，页面只返回配置与 docker 部署命令；frpc 等耗时操作返回 logId，
 * 前端通过 {@code /admin/ws/tunnel-log/{logId}} 查看实时日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/tunnel")
public class TunnelController {

    @Resource
    private TunnelService service;

    /**
     * 设置 + frps 配置预览（token 掩码）。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("info")
    public AjaxResult info() {
        return AjaxResult.ok().data(service.info());
    }

    @HasPermission("tunnel:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody TunnelSetting input) {
        service.saveSetting(input);
        return AjaxResult.ok().msg("保存成功");
    }

    /**
     * 保存 frpc 部署参数（客户端镜像）。
     */
    @HasPermission("tunnel:save")
    @PostMapping("saveDeployFrpc")
    public AjaxResult saveDeployFrpc(@RequestBody TunnelSetting input) {
        service.saveDeployFrpc(input);
        return AjaxResult.ok().msg("保存成功");
    }

    // ------------------------------------------------------------------ 节点（frpc）

    @HasPermission("tunnel:view")
    @RequestMapping("nodes")
    public AjaxResult nodes() {
        return AjaxResult.ok().data(service.listNodes());
    }

    @HasPermission("tunnel:save")
    @PostMapping("saveNode")
    public AjaxResult saveNode(@RequestBody TunnelNode input) {
        service.saveNode(input);
        return AjaxResult.ok().msg("保存成功");
    }

    /**
     * 删除节点：连同该节点的隧道与 frpc 容器一起清理。
     */
    @HasPermission("tunnel:save")
    @PostMapping("deleteNode")
    public AjaxResult deleteNode(String id) {
        return AjaxResult.ok().data(service.deleteNode(id)).msg("已开始删除节点");
    }

    /**
     * 部署 / 重建单个节点的 frpc（强制拉取镜像）。
     */
    @HasPermission("tunnel:save")
    @PostMapping("deployFrpc")
    public AjaxResult deployFrpc(String nodeId) {
        return AjaxResult.ok().data(service.deployFrpc(nodeId)).msg("已开始部署 frpc");
    }

    @HasPermission("tunnel:save")
    @PostMapping("removeFrpc")
    public AjaxResult removeFrpc(String nodeId) {
        return AjaxResult.ok().data(service.removeFrpc(nodeId)).msg("已开始移除 frpc");
    }

    /**
     * 按最新设置重建全部节点的 frpc。
     */
    @HasPermission("tunnel:save")
    @PostMapping("rebuildAllFrpc")
    public AjaxResult rebuildAllFrpc() {
        return AjaxResult.ok().data(service.rebuildAllFrpc()).msg("已开始重建全部 frpc");
    }

    // ------------------------------------------------------------------ 隧道

    @HasPermission("tunnel:view")
    @RequestMapping("tunnels")
    public AjaxResult tunnels() {
        return AjaxResult.ok().data(service.listTunnels());
    }

    /**
     * 新增隧道：自动重新生成所属节点的 frpc 配置并重建容器。
     */
    @HasPermission("tunnel:route")
    @PostMapping("addTunnel")
    public AjaxResult addTunnel(String nodeId, String appId, Integer port, String subdomain, String remark) {
        return AjaxResult.ok().data(service.addTunnel(nodeId, appId, port, subdomain, remark))
                .msg("隧道已创建，正在重建 frpc");
    }

    @HasPermission("tunnel:route")
    @PostMapping("editTunnel")
    public AjaxResult editTunnel(String id, String subdomain, Integer port, String remark) {
        return AjaxResult.ok().data(service.editTunnel(id, subdomain, port, remark))
                .msg("隧道已修改，正在重建 frpc");
    }

    @HasPermission("tunnel:route")
    @PostMapping("deleteTunnel")
    public AjaxResult deleteTunnel(String id) {
        return AjaxResult.ok().data(service.deleteTunnel(id)).msg("隧道已删除，正在重建 frpc");
    }

    /**
     * 隧道表单元数据：应用主机地址、可选端口、默认子域名。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("appMeta")
    public AjaxResult appMeta(String id) {
        return AjaxResult.ok().data(service.appMeta(id));
    }

}
