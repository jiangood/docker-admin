package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.ImageRepo;
import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.docker.admin.service.ImageRepoService;
import io.github.jiangood.docker.admin.service.ImageTagService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @HasPermission("image-repo:view")
    @RequestMapping("page")
    public AjaxResult page(String orgId, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = {"updateTime"}) Pageable pageable) {
        Spec<ImageRepo> q = Spec.of();
        q.orLike(searchText, "imageUrl", "name", "remark");

        if (StrUtil.isNotEmpty(orgId)) {
            q.eq("sysOrg.id", orgId);
        }

        q.or(qq -> {
            qq.isNull("sysOrg.id");
            qq.in("sysOrg.id", LoginTool.getOrgPermissions());
        });

        Page<ImageRepo> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
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
