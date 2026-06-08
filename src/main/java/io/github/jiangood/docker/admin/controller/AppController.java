package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.config.Config;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;


@RestController
@Slf4j
@RequestMapping("admin/app")
public class AppController {


    @Resource
    private AppService service;

    @Resource
    private Config config;


    @HasPermission("app:view")
    @RequestMapping("list")
    public AjaxResult list(String searchText, @PageableDefault(sort = {"updateTime", "createTime"}, direction = Sort.Direction.DESC) Pageable pageable, HttpSession session) {
        Spec<App> q = Spec.of();
        q.orLike(searchText,  "host.name",App.Fields.name,App.Fields.tag,App.Fields.remark);

        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        Page<App> list = service.findAll(q, pageable);
        return AjaxResult.ok().data(list);
    }

    @RequestMapping("get")
    public AjaxResult view(String id) throws UnsupportedEncodingException {
        App app = service.findById(id).orElse(null);

        if (app.getImageUrl() == null) {
            String fullUrl = config.getRegistry().getFullUrl();
            app.setImageUrl(fullUrl + "/" + app.getProject().getName());
        }

        String url = LogUrlTool.getLogViewUrl(id);
        app.setLogUrl(url);
        return AjaxResult.ok().data(app);
    }

    @RequestMapping("container")
    public AjaxResult container(String id) {
        App app = service.findById(id).orElse(null);
        Assert.state(app != null, "应用不存在");
        ContainerVo container = service.getContainerVo(app);

        return AjaxResult.ok().data(container);
    }


    @HasPermission("app:save")
    @RequestMapping("save")
    public AjaxResult save(@RequestBody App app, RequestBodyKeys requestBodyKeys) throws Exception {
        service.save(app);
        return AjaxResult.ok().msg("保存成功");
    }

    @HasPermission("app:save")
    @RequestMapping("update")
    public AjaxResult update(@RequestBody App app) {
        service.save(app);
        return AjaxResult.ok().msg("修改成功");
    }


    @HasPermission("app:save")
    @RequestMapping("updateBaseInfo")
    public AjaxResult updateBaseInfo(@RequestBody App app) {
        service.updateBaseInfo(app);
        return AjaxResult.ok().msg("修改成功");
    }

    @HasPermission("app:config")
    @RequestMapping("updateConfig")
    public AjaxResult updateConfig(String id, @RequestBody App.AppConfig appConfig) {
        App app = service.updateConfig(id, appConfig);
        service.deploy(app);

        return AjaxResult.ok().msg("修改成功，应用会自动重启").data(app);
    }


    @HasPermission("app:save")
    @RequestMapping("updateVersion")
    public AjaxResult updateVersion(String id, String version) {
        service.updateAppVersion(id, version);

        return AjaxResult.ok().msg("更新指定已发布");
    }


    @PreAuthorize("hasAuthority('app:delete')")
    @RequestMapping("delete")
    public AjaxResult delete(String id, Boolean force) {
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


    @PreAuthorize("hasAuthority('app:deploy')")
    @RequestMapping("deploy/{id}")
    public AjaxResult deploy(@PathVariable String id) {
        log.info("开始部署");
        App app = service.findById(id).orElse(null);

        service.deploy(app);
        log.info("部署指令已发送");
        return AjaxResult.ok();
    }


    @PreAuthorize("hasAuthority('app:deploy')")
    @RequestMapping("autoDeploy")
    public AjaxResult autoDeploy(String id, boolean autoDeploy) {

        App db = service.findById(id).orElse(null);
        db.setAutoDeploy(autoDeploy);

        service.save(db);


        return AjaxResult.ok().msg("调整自动发布:" + (autoDeploy ? "启用" : "停用"));
    }


    @PreAuthorize("hasAuthority('app:save')")
    @RequestMapping("start/{appId}")
    public AjaxResult start(@PathVariable String appId) {
        service.start(appId);
        return AjaxResult.ok().msg("启动指令已发送");
    }

    @PreAuthorize("hasAuthority('app:save')")
    @RequestMapping("stop/{appId}")
    public AjaxResult stop(@PathVariable String appId) {
        service.stop(appId);
        return AjaxResult.ok().msg("停止指令已发送");
    }

    @PreAuthorize("hasAuthority('app:save')")
    @RequestMapping("rename")
    public AjaxResult rename(@RequestBody Map<String, String> map) {
        String appId = map.get("appId");
        String newName = map.get("newName");
        Assert.hasText(appId, "appId不能为空");
        Assert.hasText(newName, "新名称不能为空");
        App app = service.rename(appId, newName);

        return AjaxResult.ok().msg("部署指令已发送").data(app);
    }

    @PreAuthorize("hasAuthority('app:save')")
    @RequestMapping("copyApp")
    public AjaxResult copyApp(@RequestBody @Validated MoveParam param) {
        App app = service.copyApp(param.getAppId(), param.getHostId());

        return AjaxResult.ok().msg("复制成功").data(app);
    }


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
