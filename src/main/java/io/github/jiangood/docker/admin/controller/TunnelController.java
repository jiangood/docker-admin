package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.TunnelClient;
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
 * 隧道（http-tunnel）：平台通过每个客户端自身的隧道管理 API 维护隧道。
 * <p>
 * 客户端进程由用户自行部署；这里只登记客户端并调用其管理 API，不存在容器部署、服务端 API 与异步日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/tunnel")
public class TunnelController {

    @Resource
    private TunnelService service;

    // ------------------------------------------------------------------ 客户端

    @HasPermission("tunnel:view")
    @RequestMapping("clients")
    public AjaxResult clients() {
        return AjaxResult.ok().data(service.listClients());
    }

    @HasPermission("tunnel:save")
    @PostMapping("saveClient")
    public AjaxResult saveClient(@RequestBody TunnelClient input) {
        service.saveClient(input);
        return AjaxResult.ok().msg("保存成功");
    }

    @HasPermission("tunnel:save")
    @PostMapping("deleteClient")
    public AjaxResult deleteClient(String id) {
        service.deleteClient(id);
        return AjaxResult.ok().msg("已删除客户端");
    }

    /**
     * 测试与某个客户端管理 API 的连通性。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("testClient")
    public AjaxResult testClient(String id) {
        return AjaxResult.ok().data(service.testClient(id)).msg("连接成功");
    }

    // ------------------------------------------------------------------ 隧道

    @HasPermission("tunnel:view")
    @RequestMapping("tunnels")
    public AjaxResult tunnels() {
        return AjaxResult.ok().data(service.listTunnels());
    }

}
