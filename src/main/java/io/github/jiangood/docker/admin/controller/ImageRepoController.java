package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dto.ImageExportTaskView;
import io.github.jiangood.docker.admin.dto.RemoteRegistryQuery;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.ImageRepo;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.service.ImageExportRegistry;
import io.github.jiangood.docker.admin.service.ImageExportService;
import io.github.jiangood.docker.admin.service.ImageRepoService;
import io.github.jiangood.docker.admin.service.ImageTagService;
import io.github.jiangood.docker.admin.service.RemoteSyncService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 镜像仓库：registry.url/namespace/name 形式的镜像仓库及其标签。
 */
@RestController
@RequestMapping("admin/image-repo")
public class ImageRepoController {

    @Resource
    private ImageRepoService service;

    @Resource
    private ImageTagService imageTagService;

    @Resource
    private AppRepository appRepository;

    @Resource
    private ImageExportService imageExportService;

    @Resource
    private ImageExportRegistry imageExportRegistry;

    @Resource
    private RemoteSyncService remoteSyncService;

    @HasPermission("image-repo:view")
    @RequestMapping("page")
    public AjaxResult page(String name, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        Spec<ImageRepo> q = Spec.of();
        // 关键字：供 ImageRepoPicker 等按地址或名称模糊查
        q.orLike(searchText, "imageUrl", "name", "remark");
        // 列表页「镜像名」查询条件
        if (StrUtil.isNotBlank(name)) {
            q.like("name", name);
        }

        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        Page<ImageRepo> page = service.findAll(q, pageable);
        fillLatestTag(page.getContent());
        return AjaxResult.ok().data(page);
    }

    /**
     * 给列表补上「最新版本」：每个仓库最新的非 latest 版本号。
     */
    private void fillLatestTag(List<ImageRepo> repos) {
        List<String> imageUrls = repos.stream()
                .map(ImageRepo::getImageUrl)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (imageUrls.isEmpty()) {
            return;
        }
        Map<String, String> latestTags = imageTagService.latestTagMap(imageUrls);
        repos.forEach(r -> r.setLatestTag(latestTags.get(r.getImageUrl())));
    }

    @HasPermission("image-repo:view")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        return AjaxResult.ok().data(assertRepoAccess(id));
    }

    @HasPermission("image-repo:view")
    @RequestMapping("tags")
    public AjaxResult tags(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return AjaxResult.ok().data(List.of());
        }
        ImageRepo repo = service.findByImageUrl(imageUrl);
        if (repo != null) {
            OrgAccessTool.assertAccess(repo.getSysOrg());
        }
        List<Option> options = imageTagService.tags(imageUrl).stream()
                .map(v -> new Option(v, v))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 仓库列表，供应用选择镜像（value 为镜像url）。
     */
    @HasPermission("image-repo:view")
    @RequestMapping("options")
    public AjaxResult options(String searchText) {
        Spec<ImageRepo> q = Spec.of();
        if (StrUtil.isNotBlank(searchText)) {
            q.orLike(searchText, "imageUrl", "name");
        }
        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });
        List<ImageRepo> list = service.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime"));

        List<Option> options = list.stream()
                .map(r -> new Option(r.getImageUrl(), r.getImageUrl()))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 使用该镜像仓库的应用。
     */
    @HasPermission("image-repo:view")
    @RequestMapping("apps")
    public AjaxResult apps(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return AjaxResult.ok().data(List.of());
        }
        ImageRepo repo = service.findByImageUrl(imageUrl);
        if (repo != null) {
            OrgAccessTool.assertAccess(repo.getSysOrg());
        }
        List<App> apps = appRepository.findAllByImageUrl(imageUrl);
        return AjaxResult.ok().data(apps);
    }

    /**
     * 该仓库的所有标签。
     */
    @HasPermission("image-repo:view")
    @RequestMapping("tagsDetail")
    public AjaxResult tagsDetail(String id) {
        ImageRepo repo = assertRepoAccess(id);
        List<ImageTag> tags = imageTagService.listByImageUrl(repo.getImageUrl());
        return AjaxResult.ok().data(tags);
    }

    // ------------------------------------------------------------------ 远程注册中心

    /**
     * 尝试列举系统已配置的注册中心下可补录的仓库，并标出哪些已经补录过（列表页用）。
     * <p>
     * 阿里云个人版不开放该接口，此时返回 {@code supported=false} 与说明，前端降级为手工输入镜像地址。
     */
    @HasPermission("image-repo:sync")
    @PostMapping("remote/catalog")
    public AjaxResult remoteCatalog(@RequestBody(required = false) RemoteRegistryQuery query) {
        return AjaxResult.ok().data(remoteSyncService.catalog(query));
    }

    /**
     * 补录镜像仓库（列表页）：把选中的仓库连同其版本写入本平台（来源标记 REMOTE）。
     * <p>
     * 逐仓库逐版本容错，单个失败只记入结果里的 failures，不中断整体补录。
     */
    @HasPermission("image-repo:sync")
    @PostMapping("remote/sync")
    public AjaxResult remoteSync(@RequestBody RemoteRegistryQuery query) {
        RemoteSyncResult result = remoteSyncService.sync(query);
        return AjaxResult.ok().data(result).msg(syncMsg(result, true));
    }

    /**
     * 同步单个仓库的版本（详情页「同步标签」）。
     * <p>
     * 只补该仓库下新增的版本，不新建仓库；注册中心与凭据取系统里配置生效的那一个。
     */
    @HasPermission("image-repo:sync")
    @PostMapping("remote/tags")
    public AjaxResult remoteTags(String id, Boolean withMeta) {
        ImageRepo repo = assertRepoAccess(id);
        RemoteSyncResult result = remoteSyncService.syncTags(repo.getImageUrl(),
                withMeta == null || withMeta, 100);
        return AjaxResult.ok().data(result).msg(syncMsg(result, false));
    }

    private static String syncMsg(RemoteSyncResult result, boolean withRepo) {
        StringBuilder msg = new StringBuilder("完成：");
        if (withRepo) {
            msg.append("新增 ").append(result.getRepoAdded()).append(" 个仓库、")
                    .append("跳过已存在 ").append(result.getRepoSkipped()).append(" 个仓库，");
        }
        msg.append("新增 ").append(result.getTagAdded()).append(" 个版本、")
                .append("跳过已存在 ").append(result.getTagSkipped()).append(" 个版本");
        if (result.getMetaFilled() > 0) {
            msg.append("，填充镜像元数据 ").append(result.getMetaFilled()).append(" 个");
        }
        return msg.toString();
    }

    /**
     * 导出镜像：后台从注册中心拉取并打包到临时目录，立即返回 logId。
     * <p>
     * 进度通过 {@code /admin/ws/image-export-log/{logId}} 实时查看；完成后用 {@code export/download} 下载。
     * <p>
     * 任务先在当前线程登记 {@link ImageExportRegistry}，前端列表页轮询立即可见「导出中」。
     * <p>
     * 目录容量超限时在派发任务前就失败（见 {@code assertDirCapacity}），前端点「确定」即收到提示。
     */
    @HasPermission("image-repo:export")
    @PostMapping("export/start")
    public AjaxResult exportStart(String imageUrl, String tag) {
        Assert.hasText(imageUrl, "镜像地址不能为空");
        Assert.hasText(tag, "镜像版本不能为空");
        assertRepoAccessible(imageUrl);
        imageExportService.assertDirCapacity();

        String logId = "export-" + RandomUtil.randomString(16);
        imageExportRegistry.register(logId, imageUrl, tag);
        imageExportService.export(logId, imageUrl, tag);
        return AjaxResult.ok().data(logId).msg("导出已开始");
    }

    /**
     * 某镜像仓库下所有 tag 的导出状态（列表页逐行展示下载状态、按需轮询）。
     */
    @HasPermission("image-repo:view")
    @RequestMapping("export/tasks")
    public AjaxResult exportTasks(String imageUrl) {
        Assert.hasText(imageUrl, "镜像地址不能为空");
        assertRepoAccessible(imageUrl);
        return AjaxResult.ok().data(imageExportService.taskViews(imageUrl));
    }

    /**
     * 导出任务是否已就绪（打包完成、文件可下载）。
     * <p>
     * logId 与 imageUrl + tag 两种定位方式都支持，前者用于日志结束时的一次性确认。
     */
    @HasPermission("image-repo:export")
    @RequestMapping("export/status")
    public AjaxResult exportStatus(String logId, String imageUrl, String tag) {
        ImageExportTaskView view = StrUtil.isNotBlank(logId)
                ? imageExportService.taskViewByLogId(logId)
                : imageExportService.taskView(imageUrl, tag);

        Map<String, Object> data = new LinkedHashMap<>();
        boolean ready = view != null && view.isReady();
        data.put("ready", ready);
        data.put("size", ready ? view.getSize() : 0L);
        return AjaxResult.ok().data(data);
    }

    /**
     * 下载已导出的 tar（docker save 归档，可用 {@code docker load} 导入）。
     * <p>
     * 下载成功后删除临时文件并取消登记；失败则保留以便重试（超时未下载的由清扫兜底）。
     */
    @HasPermission("image-repo:export")
    @RequestMapping("export/download")
    public void exportDownload(String logId, String imageUrl, String tag, HttpServletResponse response) throws IOException {
        // isReady 内部先校验 logId 白名单（防目录穿越），再判断任务是否已结束、文件是否存在
        Assert.state(imageExportService.isReady(logId), "导出尚未完成或文件已过期，请稍后重试");
        File file = imageExportService.fileOf(logId);

        DownloadTool.prepareDownload(response, tarName(imageUrl, tag), "application/x-tar");
        boolean ok = false;
        try {
            try (InputStream in = Files.newInputStream(file.toPath());
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
            ok = true;
        } finally {
            if (ok) {
                imageExportService.deleteAndUnregister(logId);
            }
        }
    }

    /**
     * 删除已导出的 tar（连同登记条目），给用户手动腾磁盘的入口。
     * <p>
     * 顺带覆盖两种清理：不想等 120 分钟后的清扫，或导出完就不需要了。
     */
    @HasPermission("image-repo:export")
    @PostMapping("export/delete")
    public AjaxResult exportDelete(String logId) {
        imageExportService.deleteAndUnregister(logId);
        return AjaxResult.ok().msg("已删除");
    }

    private void assertRepoAccessible(String imageUrl) {
        ImageRepo repo = service.findByImageUrl(imageUrl);
        Assert.notNull(repo, "镜像仓库不存在");
        OrgAccessTool.assertAccess(repo.getSysOrg());
    }

    /**
     * 导出文件名：镜像名最后一段 + 版本，如 app-v1.tar。
     */
    private static String tarName(String imageUrl, String tag) {
        String name = imageRepoName(imageUrl);
        return name + "-" + tag + ".tar";
    }

    private static String imageRepoName(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return "image";
        }
        String name = imageUrl.trim();
        int idx = name.lastIndexOf('/');
        if (idx >= 0 && idx < name.length() - 1) {
            name = name.substring(idx + 1);
        }
        return StrUtil.blankToDefault(name, "image");
    }

    @HasPermission("image-repo:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        ImageRepo repo = assertRepoAccess(id);
        List<App> apps = appRepository.findAllByImageUrl(repo.getImageUrl());
        Assert.state(apps.isEmpty(), "该镜像仓库下还有 " + apps.size() + " 个应用，请先删除应用");
        imageTagService.deleteByImageUrl(repo.getImageUrl());
        service.deleteById(id);
        return AjaxResult.ok().msg("删除成功");
    }

    private ImageRepo assertRepoAccess(String id) {
        ImageRepo repo = service.findById(id).orElse(null);
        Assert.notNull(repo, "镜像仓库不存在");
        OrgAccessTool.assertAccess(repo.getSysOrg());
        return repo;
    }

}
