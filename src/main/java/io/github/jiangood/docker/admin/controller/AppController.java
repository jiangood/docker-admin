package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.admin.service.TunnelService;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.config.RequestBodyKeys;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@Slf4j
@RequestMapping("admin/app")
public class AppController {


    @Resource
    private AppService service;

    @Resource
    private TunnelService tunnelService;

    @HasPermission("app:view")
    @RequestMapping("list")
    public AjaxResult list(String searchText, String hostId, String orgId, @PageableDefault(sort = {"updateTime", "createTime"}, direction = Sort.Direction.DESC) Pageable pageable, HttpSession session) {
        Spec<App> q = Spec.of();
        q.orLike(searchText,  "host.name", App.Fields.name, App.Fields.tag, App.Fields.remark);

        if (StrUtil.isNotBlank(hostId)) {
            q.eq("host.id", hostId);
        }

        if (StrUtil.isNotEmpty(orgId)) {
            q.eq("sysOrg.id", orgId);
        }

        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        Page<App> list = service.findAll(q, pageable);
        return AjaxResult.ok().data(list);
    }

    @HasPermission("app:view")
    @RequestMapping("get")
    public AjaxResult view(String id) {
        App app = assertAppAccess(id);

        String url = LogUrlTool.getLogViewUrl(id);
        app.setLogUrl(url);
        return AjaxResult.ok().data(app);
    }

    @HasPermission("app:view")
    @RequestMapping("container")
    public AjaxResult container(String id) {
        App app = assertAppAccess(id);
        ContainerVo container = service.getContainerVo(app);

        return AjaxResult.ok().data(container);
    }

    /**
     * 应用详情页「隧道」标签的元数据（当前配置、可选端口、默认前缀、可选客户端）。
     */
    @HasPermission("app:view")
    @RequestMapping("tunnelMeta")
    public AjaxResult tunnelMeta(String id) {
        assertAppAccess(id);
        return AjaxResult.ok().data(tunnelService.appTunnelMeta(id));
    }

    /**
     * 配置应用隧道：完整域名 = 域名前缀 + "." + 所选客户端域名。
     */
    @HasPermission("app:tunnel")
    @RequestMapping("updateTunnel")
    public AjaxResult updateTunnel(String id, Boolean enabled, String clientId, String prefix, Integer port) {
        assertAppAccess(id);
        boolean on = Boolean.TRUE.equals(enabled);
        App app = tunnelService.saveAppTunnel(id, on, clientId, prefix, port);
        return AjaxResult.ok().msg(on ? "隧道已开启" : "隧道已关闭").data(app);
    }

    /**
     * 容器配置元数据：镜像声明的端口/卷 + 已保存的主机侧映射。
     */
    @HasPermission("app:view")
    @RequestMapping("configMeta")
    public AjaxResult configMeta(String id) {
        App app = assertAppAccess(id);
        return AjaxResult.ok().data(service.getConfigMeta(app));
    }

    /**
     * 按 id 读取应用并校验组织数据权限，无权限时抛业务异常。
     */
    private App assertAppAccess(String id) {
        App app = service.findById(id).orElse(null);
        Assert.notNull(app, "应用不存在");
        OrgAccessTool.assertAccess(app.getSysOrg());
        return app;
    }


    @HasPermission("app:save")
    @RequestMapping("save")
    public AjaxResult save(@RequestBody App app, RequestBodyKeys requestBodyKeys) throws Exception {
        // 修改已有应用时校验数据权限（新增不受限）
        if (StrUtil.isNotBlank(app.getId())) {
            assertAppAccess(app.getId());
        }
        service.saveApp(app, requestBodyKeys);
        return AjaxResult.ok().msg("保存成功");
    }

    @HasPermission("app:save")
    @RequestMapping("updateBaseInfo")
    public AjaxResult updateBaseInfo(@RequestBody App app) {
        assertAppAccess(app.getId());
        service.updateBaseInfo(app);
        return AjaxResult.ok().msg("修改成功");
    }

    @HasPermission("app:config")
    @RequestMapping("updateConfig")
    public AjaxResult updateConfig(String id, @RequestBody App.AppConfig appConfig) {
        assertAppAccess(id);
        App app = service.updateConfig(id, appConfig);
        service.deploy(app);

        return AjaxResult.ok().msg("修改成功，应用会自动重启").data(app);
    }


    @HasPermission("app:save")
    @RequestMapping("updateVersion")
    public AjaxResult updateVersion(String id, String version) {
        assertAppAccess(id);
        service.updateAppVersion(id, version);

        return AjaxResult.ok().msg("更新指定已发布");
    }


    /**
     * 该应用镜像可选的版本（来自成功构建记录）。
     */
    @HasPermission("app:view")
    @RequestMapping("versions")
    public AjaxResult versions(String id) {
        App app = assertAppAccess(id);
        String imageUrl = app.getImageUrl();
        List<Option> options = service.getImageVersions(imageUrl).stream()
                .map(v -> new Option(v, v))
                .toList();
        return AjaxResult.ok().data(options);
    }


    @HasPermission("app:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id, Boolean force) {
        assertAppAccess(id);
        if (force != null && force) {
            service.deleteById(id);
            return AjaxResult.ok().msg("强制删除数据成功");
        }

        try {
            service.deleteApp(id);
        } catch (Exception e) {
            log.error("删除应用失败", e);

            return AjaxResult.err().msg("删除失败");
        }

        return AjaxResult.ok();
    }


    @HasPermission("app:deploy")
    @RequestMapping("deploy/{id}")
    public AjaxResult deploy(@PathVariable String id) {
        log.info("开始部署");
        App app = assertAppAccess(id);

        service.deploy(app);
        log.info("部署指令已发送");
        return AjaxResult.ok();
    }


    @HasPermission("app:deploy")
    @RequestMapping("autoDeploy")
    public AjaxResult autoDeploy(String id, boolean autoDeploy) {

        App db = assertAppAccess(id);
        db.setAutoDeploy(autoDeploy);

        service.save(db);


        return AjaxResult.ok().msg("调整自动发布:" + (autoDeploy ? "启用" : "停用"));
    }


    @HasPermission("app:save")
    @RequestMapping("start/{appId}")
    public AjaxResult start(@PathVariable String appId) {
        assertAppAccess(appId);
        service.start(appId);
        return AjaxResult.ok().msg("启动指令已发送");
    }

    @HasPermission("app:save")
    @RequestMapping("stop/{appId}")
    public AjaxResult stop(@PathVariable String appId) {
        assertAppAccess(appId);
        service.stop(appId);
        return AjaxResult.ok().msg("停止指令已发送");
    }

    @HasPermission("app:save")
    @RequestMapping("rename")
    public AjaxResult rename(@RequestBody Map<String, String> map) {
        String appId = map.get("appId");
        String newName = map.get("newName");
        Assert.hasText(appId, "appId不能为空");
        Assert.hasText(newName, "新名称不能为空");
        assertAppAccess(appId);
        App app = service.rename(appId, newName);

        return AjaxResult.ok().msg("部署指令已发送").data(app);
    }

    @HasPermission("app:save")
    @RequestMapping("copyApp")
    public AjaxResult copyApp(@RequestBody @Validated MoveParam param) {
        assertAppAccess(param.getAppId());
        App app = service.copyApp(param.getAppId(), param.getHostId());

        return AjaxResult.ok().msg("复制成功").data(app);
    }


    @HasPermission("app:view")
    @RequestMapping("options")
    public AjaxResult options(String searchText) {
        Spec<App> q = Spec.of();
        if (StrUtil.isNotBlank(searchText)) {
            q.like("name", searchText);
        }

        List<App> list = service.findAll(q, Sort.unsorted());
        List<Option> options = Option.convertList(list, App::getId, App::getName);

        return AjaxResult.ok().data(options);
    }






    @Data
    public static class MoveParam {

        @NotNull
        String appId;

        @NotNull
        String hostId;

    }
}
