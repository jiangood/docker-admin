package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import io.admin.common.dto.AjaxResult;
import io.admin.common.dto.antd.Option;
import io.admin.framework.config.argument.RequestBodyKeys;
import io.admin.framework.config.security.HasPermission;
import io.admin.framework.data.query.JpaQuery;
import io.admin.modules.common.LoginUtils;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@RestController
@Slf4j
@RequestMapping("admin/app")
public class AppController {


    @Resource
    private AppService service;

    @Resource
    private Config config;

    @Resource
    private DockerClientManager sdk;

    @HasPermission("app:list")
    @RequestMapping("list")
    public AjaxResult list(String groupId, String searchText, @PageableDefault(sort = {"updateTime", "createTime"}, direction = Sort.Direction.DESC) Pageable pageable, HttpSession session) {
        JpaQuery<App> q = new JpaQuery<>();
        q.searchText(searchText, "name", "remark", "host.name");
        q.eq(App.Fields.appGroup + ".id", groupId);

        q.addSubOr(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginUtils.getOrgPermissions());
        });

        Page<App> list = service.findPageByRequest(q, pageable);
        return AjaxResult.ok().data(list);
    }

    @RequestMapping("get")
    public AjaxResult view(String id) throws UnsupportedEncodingException {
        App app = service.findByRequest(id);

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
        App app = service.findOne(id);
        Assert.state(app != null, "应用不存在");
        ContainerVo container = service.getContainerVo(app);

        return AjaxResult.ok().data(container);
    }


    @HasPermission("app:save")
    @RequestMapping("save")
    public AjaxResult save(@RequestBody App app, RequestBodyKeys requestBodyKeys) throws Exception {
        service.saveOrUpdateByRequest(app, requestBodyKeys);
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


    @HasPermission(value = "app:config")
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


    @HasPermission("app:delete")
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


    @HasPermission("app:deploy")
    @RequestMapping("deploy/{id}")
    public AjaxResult deploy(@PathVariable String id) {
        log.info("开始部署");
        App app = service.findOne(id);

        service.deploy(app);
        log.info("部署指令已发送");
        return AjaxResult.ok();
    }


    @HasPermission(value = "app:deploy")
    @RequestMapping("autoDeploy")
    public AjaxResult autoDeploy(String id, boolean autoDeploy) {

        App db = service.findByRequest(id);
        db.setAutoDeploy(autoDeploy);

        service.save(db);


        return AjaxResult.ok().msg("调整自动发布:" + (autoDeploy ? "启用" : "停用"));
    }


    @HasPermission(value = "app:save")
    @RequestMapping("start/{appId}")
    public AjaxResult start(@PathVariable String appId) {
        service.start(appId);
        return AjaxResult.ok().msg("启动指令已发送");
    }

    @HasPermission(value = "app:save")
    @RequestMapping("stop/{appId}")
    public AjaxResult stop(@PathVariable String appId) {
        service.stop(appId);
        return AjaxResult.ok().msg("停止指令已发送");
    }

    @HasPermission(value = "app:save")
    @RequestMapping("rename")
    public AjaxResult rename(@RequestBody Map<String, String> map) {
        String appId = map.get("appId");
        String newName = map.get("newName");
        Assert.hasText(appId, "appId不能为空");
        Assert.hasText(newName, "新名称不能为空");
        App app = service.rename(appId, newName);

        return AjaxResult.ok().msg("部署指令已发送").data(app);
    }

    @HasPermission(value = "app:save")
    @RequestMapping("copyApp")
    public AjaxResult copyApp(@RequestBody @Validated MoveParam param) {
        App app = service.copyApp(param.getAppId(), param.getHostId());

        return AjaxResult.ok().msg("复制成功").data(app);
    }


    @RequestMapping("options")
    public AjaxResult options(String searchText) {
        JpaQuery<App> q = new JpaQuery<>();
        if (StrUtil.isNotBlank(searchText)) {
            q.like("name", searchText);
        }

        List<App> list = service.findAll(q);
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
