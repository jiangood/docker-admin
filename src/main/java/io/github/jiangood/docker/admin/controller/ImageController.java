package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.dto.ImageSummary;
import io.github.jiangood.docker.admin.service.ImageService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 镜像视图（基于构建记录）。
 */
@RestController
@Slf4j
@RequestMapping("admin/image")
public class ImageController {

    @Resource
    private ImageService service;

    @HasPermission("image:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText) {
        List<ImageSummary> list = service.listImages(searchText);
        return AjaxResult.ok().data(new PageImpl<>(list));
    }

    @HasPermission("image:view")
    @RequestMapping("tags")
    public AjaxResult tags(String imageUrl) {
        List<Option> options = service.tags(imageUrl).stream().map(v -> new Option(v, v)).toList();
        return AjaxResult.ok().data(options);
    }

    @HasPermission("image:view")
    @RequestMapping("apps")
    public AjaxResult apps(String imageUrl) {
        return AjaxResult.ok().data(service.apps(imageUrl));
    }

    /**
     * 供「新增应用」的镜像下拉使用。
     */
    @HasPermission("image:view")
    @RequestMapping("options")
    public AjaxResult options(String searchText) {
        List<Option> options = service.listImages(searchText).stream()
                .map(img -> new Option(img.getImageUrl(), img.getImageUrl()))
                .toList();
        return AjaxResult.ok().data(options);
    }
}
