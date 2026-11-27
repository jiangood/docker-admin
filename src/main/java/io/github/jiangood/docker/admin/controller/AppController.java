package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.docker.admin.dto.ContainerVo;
import io.github.jiangood.docker.admin.config.DockerSettings;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.DeployTrigger;
import io.github.jiangood.docker.admin.service.AppService;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.websocket.DockerLogService;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;


@RestController
@Slf4j
@RequestMapping("admin/app")
public class AppController {

    @Resource
    private AppService service;

    @Resource
    private ContainerService containerService;

    @Resource
    private DockerLogService dockerLogService;

    @Resource
    private DockerSettings dockerSettings;

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
     * 应用的部署记录列表（最新在前），供「部署日志」标签按次查看。
     */
    @HasPermission("app:view")
    @RequestMapping("deployLogs")
    public AjaxResult deployLogs(String id) {
        assertAppAccess(id);
        return AjaxResult.ok().data(service.listDeployLogs(id));
    }

    /**
     * 按时间段查询应用容器日志（末尾最多为系统参数的行数，默认 10000 行）。
     * {@code since}/{@code until} 为秒级时间戳，返回带 Docker 时间戳前缀的文本。
     */
    @HasPermission("app:view")
    @RequestMapping("containerLogs")
    public AjaxResult containerLogs(String id, Integer since, Integer until) throws InterruptedException {
        App app = assertAppAccess(id);
        Container container = service.getContainer(app);
        Assert.notNull(container, "容器未部署");
        assertTimeRange(since, until);
        return AjaxResult.ok().data(dockerLogService.readContainerLogs(app.getHost(), container.getId(),
                since, until, dockerSettings.logRangeTail()));
    }

    /**
     * 下载应用容器日志。只允许最近 N 天（系统参数，默认 7 天）：未传时间范围时按最近 N 天，
     * 传入的范围早于该窗口时直接拒绝；末尾最多为系统参数的行数（默认 10000 行）。
     * 只读历史，不跟随实时输出，避免下载请求长期占用连接。
     */
    @HasPermission("app:view")
    @RequestMapping("containerLogDownload")
    public void containerLogDownload(String id, Integer since, Integer until,
                                     HttpServletResponse response) throws Exception {
        App app = assertAppAccess(id);
        Container container = service.getContainer(app);
        Assert.notNull(container, "容器未部署");
        assertTimeRange(since, until);

        int days = dockerSettings.logDownloadDays();
        Integer downloadSince = resolveDownloadSince(since, days);
        assertTimeRange(downloadSince, until);
        String fileName = StrUtil.blankToDefault(app.getName(), "app")
                + "-" + StrUtil.subPre(container.getId(), 12) + ".log";
        DownloadTool.prepareDownload(response, DownloadTool.safeFileName(fileName), "text/plain;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            dockerLogService.writeContainerLogs(app.getHost(), container.getId(), downloadSince, until,
                    dockerSettings.logRangeTail(), out);
            out.flush();
        }
    }

    private static void assertTimeRange(Integer since, Integer until) {
        if (since != null && until != null && since > until) {
            throw new BusinessException("开始时间不能晚于结束时间");
        }
    }

    /**
     * 下载窗口的起点：未指定时取最近 N 天；早于窗口时拒绝，避免一次下载过多历史日志。
     */
    private static Integer resolveDownloadSince(Integer since, int days) {
        long earliest = System.currentTimeMillis() / 1000 - days * 86400L;
        if (since == null) {
            return (int) earliest;
        }
        if (since < earliest) {
            throw new BusinessException("日志下载最多支持最近 " + days + " 天，请缩小时间范围");
        }
        return since;
    }

    /**
     * 应用容器的详情（基本信息 + 只读配置），供应用详情页的通用容器组件使用。
     * 与主机维度的 {@code admin/container/inspect} 相对，这里额外做了组织数据权限校验。
     */
    @HasPermission("app:view")
    @RequestMapping("containerDetail")
    public AjaxResult containerDetail(String id) {
        App app = assertAppAccess(id);
        Container container = service.getContainer(app);
        Assert.notNull(container, "容器未部署");
        return AjaxResult.ok().data(containerService.inspect(app.getHost(), container.getId()));
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
     * 按镜像地址 + 版本读取容器配置元数据（镜像声明的端口/卷）。
     * 供新建应用 / docker run 等尚无应用记录的场景使用。
     */
    @HasPermission("app:view")
    @RequestMapping("configMetaByImage")
    public AjaxResult configMetaByImage(String imageUrl, String imageTag) {
        return AjaxResult.ok().data(service.getImageConfigMeta(imageUrl, imageTag));
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
    public AjaxResult save(@RequestBody App app, @RequestHeader("X-Body-Fields") List<String> requestBodyKeys) throws Exception {
        // 修改已有应用时校验数据权限（新增不受限）
        if (StrUtil.isNotBlank(app.getId())) {
            assertAppAccess(app.getId());
        }
        service.saveApp(app, requestBodyKeys);
        return AjaxResult.ok().msg("保存成功");
    }

    /**
     * 新建应用并立即部署（新增应用弹窗「确定后直接部署」）。
     */
    @HasPermission("app:deploy")
    @RequestMapping("saveAndDeploy")
    public AjaxResult saveAndDeploy(@RequestBody App app) throws Exception {
        App saved = service.saveApp(app, null);
        service.deploy(saved, false, DeployTrigger.CREATE);
        return AjaxResult.ok().msg("部署指令已发送");
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
        service.deploy(app, false, DeployTrigger.CONFIG);

        return AjaxResult.ok().msg("修改成功，应用会自动重启").data(app);
    }


    @HasPermission("app:save")
    @RequestMapping("updateVersion")
    public AjaxResult updateVersion(String id, String version, Boolean forcePull) {
        assertAppAccess(id);
        service.updateAppVersion(id, version, Boolean.TRUE.equals(forcePull));

        return AjaxResult.ok().msg("部署指令已发送，请前往「部署日志」查看进度");
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
            service.forceDeleteApp(id);
            return AjaxResult.ok().msg("强制删除数据成功");
        }

        try {
            service.deleteApp(id);
        } catch (BusinessException e) {
            // 业务校验失败：原样返回原因，前端据此不走「强制删除数据」兜底
            throw e;
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
        App app = service.copyApp(param.getAppId(), param.getHostId(), param.getName());

        return AjaxResult.ok().msg("复制成功").data(app);
    }


    /**
     * 迁移应用：在原主机停止并移除容器，切换到目标主机后重新部署。
     */
    @HasPermission("app:migrate")
    @RequestMapping("migrateApp")
    public AjaxResult migrateApp(@RequestBody @Validated MoveParam param) {
        assertAppAccess(param.getAppId());
        App app = service.migrateApp(param.getAppId(), param.getHostId());

        return AjaxResult.ok().msg("迁移成功，正在目标主机重新部署").data(app);
    }


    /**
     * 校验应用名称是否已被占用（复制应用时前端先行校验）。
     */
    @HasPermission("app:view")
    @RequestMapping("existsName")
    public AjaxResult existsName(String name) {
        return AjaxResult.ok().data(service.existsByName(name));
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

        /**
         * 新应用名称；为空时后端自动生成
         */
        String name;

    }
}
