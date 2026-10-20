package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dto.ImageExportTaskView;
import io.github.jiangood.docker.admin.dto.ManualImageRequest;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.service.ImageExportRegistry;
import io.github.jiangood.docker.admin.service.ImageExportService;
import io.github.jiangood.docker.admin.service.ImageImportRegistry;
import io.github.jiangood.docker.admin.service.ImageImportService;
import io.github.jiangood.docker.admin.service.ImageManualService;
import io.github.jiangood.docker.admin.service.ImageService;
import io.github.jiangood.docker.admin.service.ImageTagService;
import io.github.jiangood.docker.admin.service.RegistryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 镜像：registry.url/namespace/name 形式的镜像及其标签。
 */
@RestController
@RequestMapping("admin/image")
public class ImageController {

    @Resource
    private ImageService service;

    @Resource
    private ImageTagService imageTagService;

    @Resource
    private AppRepository appRepository;

    @Resource
    private ImageExportService imageExportService;

    @Resource
    private ImageExportRegistry imageExportRegistry;

    @Resource
    private ImageImportService imageImportService;

    @Resource
    private ImageImportRegistry imageImportRegistry;

    @Resource
    private RegistryService registryService;

    @Resource
    private RemoteSyncService remoteSyncService;

    @Resource
    private ImageManualService imageManualService;

    @HasPermission("image:view")
    @RequestMapping("page")
    public AjaxResult page(String name, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        Spec<Image> q = Spec.of();
        // 关键字：供 ImagePicker 等按地址或名称模糊查
        q.orLike(searchText, "imageUrl", "name", "remark");
        // 列表页「镜像名」查询条件
        if (StrUtil.isNotBlank(name)) {
            q.like("name", name);
        }

        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        Page<Image> page = service.findAll(q, pageable);
        fillLatestTag(page.getContent());
        return AjaxResult.ok().data(page);
    }

    /**
     * 给列表补上「最新版本」：每个镜像最新的非 latest 版本号。
     */
    private void fillLatestTag(List<Image> images) {
        List<String> imageUrls = images.stream()
                .map(Image::getImageUrl)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (imageUrls.isEmpty()) {
            return;
        }
        Map<String, String> latestTags = imageTagService.latestTagMap(imageUrls);
        images.forEach(image -> image.setLatestTag(latestTags.get(image.getImageUrl())));
    }

    @HasPermission("image:view")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        return AjaxResult.ok().data(assertImageAccess(id));
    }

    @HasPermission("image:view")
    @RequestMapping("tags")
    public AjaxResult tags(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return AjaxResult.ok().data(List.of());
        }
        Image image = service.findByImageUrl(imageUrl);
        if (image != null) {
            OrgAccessTool.assertAccess(image.getSysOrg());
        }
        List<Option> options = imageTagService.tags(imageUrl).stream()
                .map(v -> new Option(v, v))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 镜像列表，供应用选择镜像（value 为镜像url）。
     */
    @HasPermission("image:view")
    @RequestMapping("options")
    public AjaxResult options(String searchText) {
        Spec<Image> q = Spec.of();
        if (StrUtil.isNotBlank(searchText)) {
            q.orLike(searchText, "imageUrl", "name");
        }
        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });
        List<Image> list = service.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime"));

        List<Option> options = list.stream()
                .map(image -> new Option(image.getImageUrl(), image.getImageUrl()))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 使用该镜像的应用。
     */
    @HasPermission("image:view")
    @RequestMapping("apps")
    public AjaxResult apps(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return AjaxResult.ok().data(List.of());
        }
        Image image = service.findByImageUrl(imageUrl);
        if (image != null) {
            OrgAccessTool.assertAccess(image.getSysOrg());
        }
        List<App> apps = appRepository.findAllByImageUrl(imageUrl);
        return AjaxResult.ok().data(apps);
    }

    /**
     * 该镜像的所有标签。
     */
    @HasPermission("image:view")
    @RequestMapping("tagsDetail")
    public AjaxResult tagsDetail(String id) {
        Image image = assertImageAccess(id);
        List<ImageTag> tags = imageTagService.listByImageUrl(image.getImageUrl());
        return AjaxResult.ok().data(tags);
    }

    // ------------------------------------------------------------------ 补录

    /**
     * 手工补录镜像（列表页）：镜像地址与版本都由用户填写，不访问注册中心。
     * <p>
     * 已存在的镜像与版本只计数跳过，不覆盖已有来源与元数据。
     */
    @HasPermission("image:sync")
    @PostMapping("manual/save")
    public AjaxResult manualSave(@RequestBody ManualImageRequest request) {
        RemoteSyncResult result = imageManualService.save(request);
        return AjaxResult.ok().data(result).msg(syncMsg(result, true));
    }

    /**
     * 同步单个镜像的版本（详情页「同步标签」）。
     * <p>
     * 只补该镜像下新增的版本，不新建镜像；注册中心与凭据取系统里配置生效的那一个。
     */
    @HasPermission("image:sync")
    @PostMapping("remote/tags")
    public AjaxResult remoteTags(String id, Boolean withMeta) {
        Image image = assertImageAccess(id);
        RemoteSyncResult result = remoteSyncService.syncTags(image.getImageUrl(),
                withMeta == null || withMeta, 100, image.getRegistryId());
        return AjaxResult.ok().data(result).msg(syncMsg(result, false));
    }

    private static String syncMsg(RemoteSyncResult result, boolean withImage) {
        StringBuilder msg = new StringBuilder("完成：");
        if (withImage) {
            msg.append("新增 ").append(result.getImageAdded()).append(" 个镜像、")
                    .append("跳过已存在 ").append(result.getImageSkipped()).append(" 个镜像，");
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
    @HasPermission("image:export")
    @PostMapping("export/start")
    public AjaxResult exportStart(String imageUrl, String tag) {
        Assert.hasText(imageUrl, "镜像地址不能为空");
        Assert.hasText(tag, "镜像版本不能为空");
        assertImageAccessible(imageUrl);
        imageExportService.assertDirCapacity();

        String logId = "export-" + RandomUtil.randomString(16);
        imageExportRegistry.register(logId, imageUrl, tag);
        imageExportService.export(logId, imageUrl, tag);
        return AjaxResult.ok().data(logId).msg("导出已开始");
    }

    /**
     * 某镜像下所有 tag 的导出状态（列表页逐行展示下载状态、按需轮询）。
     */
    @HasPermission("image:view")
    @RequestMapping("export/tasks")
    public AjaxResult exportTasks(String imageUrl) {
        Assert.hasText(imageUrl, "镜像地址不能为空");
        assertImageAccessible(imageUrl);
        return AjaxResult.ok().data(imageExportService.taskViews(imageUrl));
    }

    /**
     * 导出任务是否已就绪（打包完成、文件可下载）。
     * <p>
     * logId 与 imageUrl + tag 两种定位方式都支持，前者用于日志结束时的一次性确认。
     */
    @HasPermission("image:export")
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
    @HasPermission("image:export")
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
    @HasPermission("image:export")
    @PostMapping("export/delete")
    public AjaxResult exportDelete(String logId) {
        imageExportService.deleteAndUnregister(logId);
        return AjaxResult.ok().msg("已删除");
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 上传 tar 并导入到注册中心：先在平台所在主机上 {@code docker load}，再打上注册中心前缀并 push，
     * 最后登记镜像与版本（来源 IMPORT）。
     * <p>
     * 上传完成后立即返回 logId，载入与推送在后台执行；前端通过
     * {@code /admin/ws/image-import-log/{logId}} 看实时日志，用 {@code import/status} 取最终结果。
     * <p>
     * 镜像名与版本可留空，此时沿用 tar 内的 RepoTags；指定镜像名时归档里只能有一个镜像。
     */
    @HasPermission("image:import")
    @PostMapping("import/start")
    public AjaxResult importStart(@RequestParam("file") MultipartFile file,
                                  String registryId, String imageName, String tag) throws IOException {
        Assert.notNull(file, "请选择要导入的 tar 包");
        Assert.state(!file.isEmpty(), "tar 包为空，无法导入");

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        Assert.state(StrUtil.endWithIgnoreCase(filename, ".tar"),
                "仅支持 docker save 生成的 .tar 归档");

        String name = StrUtil.trimToNull(imageName);
        String version = StrUtil.trimToNull(tag);
        imageImportService.validate(name, version);

        // 同步校验注册中心，错误直接返回给前端，不必等后台任务跑起来才在日志里报错
        registryService.requireRegistry(registryId);

        String logId = "import-" + RandomUtil.randomString(16);
        File target = imageImportService.newTarFile(logId);
        try {
            file.transferTo(target);
        } catch (IOException | IllegalStateException e) {
            FileUtil.del(target);
            throw e;
        }

        imageImportRegistry.register(logId);
        imageImportService.importTar(logId, target, registryId, name, version);
        return AjaxResult.ok().data(logId).msg("已开始导入");
    }

    /**
     * 导入任务状态：RUNNING / SUCCESS / FAILED，附带结果说明与已导入的镜像地址。
     */
    @HasPermission("image:import")
    @RequestMapping("import/status")
    public AjaxResult importStatus(String logId) {
        return AjaxResult.ok().data(imageImportRegistry.get(logId));
    }

    private void assertImageAccessible(String imageUrl) {
        Image image = service.findByImageUrl(imageUrl);
        Assert.notNull(image, "镜像不存在");
        OrgAccessTool.assertAccess(image.getSysOrg());
    }

    /**
     * 导出文件名：镜像名最后一段 + 版本，如 app-v1.tar。
     */
    private static String tarName(String imageUrl, String tag) {
        String name = imageName(imageUrl);
        return name + "-" + tag + ".tar";
    }

    private static String imageName(String imageUrl) {
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

    @HasPermission("image:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        Image image = assertImageAccess(id);
        List<App> apps = appRepository.findAllByImageUrl(image.getImageUrl());
        Assert.state(apps.isEmpty(), "该镜像下还有 " + apps.size() + " 个应用，请先删除应用");
        imageTagService.deleteByImageUrl(image.getImageUrl());
        service.deleteById(id);
        return AjaxResult.ok().msg("删除成功");
    }

    private Image assertImageAccess(String id) {
        Image image = service.findById(id).orElse(null);
        Assert.notNull(image, "镜像不存在");
        OrgAccessTool.assertAccess(image.getSysOrg());
        return image;
    }

}
