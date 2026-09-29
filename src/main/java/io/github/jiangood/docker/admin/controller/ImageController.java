package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.BuildRequest;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.ImageService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.config.RequestBodyKeys;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像：从代码仓库构建、带版本的镜像仓库。
 */
@RestController
@RequestMapping("admin/image")
public class ImageController {
    @Resource
    private ImageService service;

    @Resource
    private BuildLogService logService;


    @HasPermission("image:view")
    @RequestMapping("page")
    public AjaxResult page(String orgId, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        Spec<Image> q = buildQuery();
        q.orLike(searchText, "name", "cnName", "remark");


        if (StrUtil.isNotEmpty(orgId)) {
            q.eq("sysOrg.id", orgId);
        }


        Page<Image> page = this.service.findAll(q, pageable);

        return AjaxResult.ok().data(page);
    }

    private Spec<Image> buildQuery() {
        Spec<Image> q = Spec.of();
        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        return q;
    }

    @HasPermission("image:save")
    @PostMapping({"save"})
    public AjaxResult save(@RequestBody Image param, RequestBodyKeys updateFields) throws Exception {
        // 修改已有镜像时校验数据权限（新增不受限）
        if (StrUtil.isNotBlank(param.getId())) {
            assertImageAccess(param.getId());
        }
        if (param.getSysOrg() == null || param.getSysOrg().getId() == null) {
            param.setSysOrg(null);
        }
        param.setGitUrl(param.getGitUrl().trim());
        param.setName(param.getName().trim());
        Image result = this.service.saveImage(param, updateFields);
        return AjaxResult.ok().data(result.getId()).msg("保存成功");
    }


    @HasPermission("image:delete")
    @PostMapping({"delete"})
    public AjaxResult delete(String id) {
        assertImageAccess(id);
        this.service.deleteImage(id);
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("image:view")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        Image image = assertImageAccess(id);
        return AjaxResult.ok().data(image);
    }

    /**
     * 按 id 读取镜像并校验组织数据权限，无权限时抛业务异常。
     */
    private Image assertImageAccess(String id) {
        Image image = service.findById(id).orElse(null);
        Assert.notNull(image, "镜像不存在");
        OrgAccessTool.assertAccess(image.getSysOrg());
        return image;
    }


    @HasPermission("image:build")
    @RequestMapping("build")
    public AjaxResult build(BuildRequest buildRequest, @RequestParam String imageId, String buildHostId) throws IOException {
        Image image = assertImageAccess(imageId);
        service.checkBuildImage();
        Assert.isTrue(ImageService.isValidTag(buildRequest.getTag()), "tag 格式不正确，需形如 v1.0.1");

        // 更新最近时间,方便排序
        image.setUpdateTime(LocalDateTime.now());
        image = service.save(image);

        buildRequest.setImageId(image.getId());
        buildRequest.setDockerfile(image.getDockerfile());
        buildRequest.setBuildHostId(buildHostId);
        service.buildImage(buildRequest);

        return AjaxResult.ok().msg("构建已触发");
    }

    /**
     * 远程 tag 列表（只保留 vX.Y.Z 形式的版本 tag）。
     */
    @HasPermission("image:view")
    @RequestMapping("tags")
    public AjaxResult tags(String imageId) {
        Image image = assertImageAccess(imageId);
        List<Option> options = service.listRemoteTags(image).stream()
                .filter(ImageService::isValidTag)
                .map(t -> new Option(t, t))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 重置镜像的 webhook token。
     */
    @HasPermission("image:webhook")
    @RequestMapping("resetWebhook")
    public AjaxResult resetWebhook(String id) {
        Image image = assertImageAccess(id);
        image.setWebhookToken(RandomUtil.randomString(32));
        service.save(image);
        return AjaxResult.ok().msg("已重置").data(image.getWebhookToken());
    }

    @HasPermission("image:build")
    @RequestMapping("stopBuild")
    public AjaxResult stopBuild(@RequestParam String id) throws IOException {
        BuildLog buildLog = logService.findById(id).orElse(null);
        Assert.notNull(buildLog, "构建记录不存在");
        assertImageAccess(buildLog.getImageId());
        service.stopBuild(id);

        return AjaxResult.ok();
    }

    @HasPermission("image:build")
    @RequestMapping("cleanErrorLog")
    public AjaxResult cleanErrorLog(@RequestParam String id) {
        assertImageAccess(id);
        service.cleanErrorLog(id);
        return AjaxResult.ok();
    }


    @HasPermission("image:view")
    @RequestMapping("options")
    public AjaxResult options() {
        Spec<Image> q = buildQuery();

        List<Image> list = service.findAll(q, Sort.by(Sort.Direction.DESC, "updateTime"));

        List<Option> options = new ArrayList<>();
        for (Image h : list) {
            options.add(new Option(h.getId(), h.getName()));
        }


        return AjaxResult.ok().data(options);
    }

    /**
     * 该镜像的可用版本（tag），倒序，供应用选择。
     */
    @HasPermission("image:view")
    @RequestMapping("versions")
    public AjaxResult versions(String imageId) {
        assertImageAccess(imageId);
        List<Option> options = service.tags(imageId).stream()
                .map(v -> new Option(v, v))
                .toList();
        return AjaxResult.ok().data(options);
    }

    /**
     * 使用该镜像的应用。
     */
    @HasPermission("image:view")
    @RequestMapping("apps")
    public AjaxResult apps(String imageId) {
        assertImageAccess(imageId);
        List<App> apps = service.apps(imageId);
        for (App app : apps) {
            app.setImageUrl(service.getFullImageUrl(app.getImage()));
        }
        return AjaxResult.ok().data(apps);
    }

}
