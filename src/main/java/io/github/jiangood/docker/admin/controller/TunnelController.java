package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.entity.TunnelSetting;
import io.github.jiangood.docker.admin.service.TunnelService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隧道（nps）：选一台主机作为 nps 服务端并容器化部署，为各节点下发 npc 客户端，
 * 按应用配置维护 nps 侧的域名解析。
 * <p>
 * 过程日志通过 {@code /admin/ws/tunnel-log/{logId}} 实时输出。
 */
@RestController
@Slf4j
@RequestMapping("admin/tunnel")
public class TunnelController {

    @Resource
    private TunnelService service;

    /**
     * 设置 + 掩码后的 conf 原文。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("info")
    public AjaxResult info() {
        TunnelSetting setting = service.getSetting();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("setting", setting);
        data.put("conf", service.confForView());
        return AjaxResult.ok().data(data);
    }

    @HasPermission("tunnel:view")
    @RequestMapping("conf")
    public AjaxResult conf() {
        return AjaxResult.ok().data(service.confForView());
    }

    /**
     * 按表单字段渲染默认 conf，供编辑器初始化 / 重置。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("generateConf")
    public AjaxResult generateConf(@RequestBody TunnelSetting input) {
        return AjaxResult.ok().data(service.previewConf(input));
    }

    @HasPermission("tunnel:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody TunnelSetting input) {
        service.saveSetting(input);
        return AjaxResult.ok().msg("保存成功");
    }

    /**
     * 部署 / 重启 nps。
     */
    @HasPermission("tunnel:deploy")
    @PostMapping("deployNps")
    public AjaxResult deployNps() {
        service.requireSetting();
        String logId = newLogId();
        service.deployNps(logId);
        return AjaxResult.ok().data(logId).msg("部署已开始");
    }

    /**
     * 功能总开关。
     */
    @HasPermission("tunnel:deploy")
    @PostMapping("toggle")
    public AjaxResult toggle(boolean enabled) {
        service.requireSetting();
        String logId = newLogId();
        service.toggle(enabled, logId);
        return AjaxResult.ok().data(logId).msg(enabled ? "正在启用..." : "正在停用...");
    }

    /**
     * 各主机上的 nps / npc 容器（含残留）。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("containers")
    public AjaxResult containers() {
        return AjaxResult.ok().data(service.listContainers());
    }

    /**
     * 手动清理容器。
     */
    @HasPermission("tunnel:clean")
    @PostMapping("clean")
    public AjaxResult clean(@RequestBody List<String> containerIds) {
        Assert.notEmpty(containerIds, "请选择要清理的容器");
        String logId = newLogId();
        service.cleanContainers(containerIds, logId);
        return AjaxResult.ok().data(logId).msg("清理已开始");
    }

    /**
     * 连通性检测。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("probe")
    public AjaxResult probe(String hostId) {
        return AjaxResult.ok().data(service.probe(hostId));
    }

    /**
     * 节点（npc）列表。
     */
    @HasPermission("tunnel:view")
    @RequestMapping("nodes")
    public AjaxResult nodes() {
        return AjaxResult.ok().data(service.listNodes());
    }

    /**
     * 手动重新同步某个节点的域名解析。
     */
    @HasPermission("tunnel:deploy")
    @PostMapping("syncNode")
    public AjaxResult syncNode(String hostId) {
        Assert.hasText(hostId, "请选择节点");
        String logId = newLogId();
        service.syncNode(hostId, logId);
        return AjaxResult.ok().data(logId).msg("同步已开始");
    }

    private static String newLogId() {
        return "tunnel-" + RandomUtil.randomString(16);
    }

}
