package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.docker.admin.service.ImageService;
import io.github.jiangood.docker.base.OrgAccessTool;
import io.github.jiangood.openadmin.framework.auth.LoginTool;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@Slf4j
@RequestMapping(value = "admin/buildLog")
public class BuildLogController {

    @Resource
    private BuildLogService service;

    @Resource
    private ImageService imageService;

    @HasPermission("image:view")
    @RequestMapping("list")
    public AjaxResult list(String imageId, @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        Spec<BuildLog> q = Spec.of();
        if (StrUtil.isNotBlank(imageId)) {
            // 指定镜像时必须校验该镜像的数据权限
            Image image = imageService.findById(imageId).orElse(null);
            Assert.notNull(image, "镜像不存在");
            OrgAccessTool.assertAccess(image.getSysOrg());
            q.eq("imageId", imageId);
        } else {
            // 未指定镜像时限制在当前用户可访问的镜像范围内
            List<String> imageIds = accessibleImageIds();
            if (imageIds.isEmpty()) {
                // 无可访问镜像时构造一个永假条件，避免空 IN 查询
                q.eq("imageId", "__no_access__");
            } else {
                q.in("imageId", imageIds);
            }
        }
        Page<BuildLog> page = service.findAll(q, pageable);


        for (BuildLog log : page) {
            log.setLogUrl(LogUrlTool.getLogViewUrl(log.getId()));
            if (log.getTimeSpend() == null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), LocalDateTime.now()).toMillis());
            }
        }

        return AjaxResult.ok().data( page);
    }

    /**
     * 当前用户可访问的镜像 id 列表（管理员返回全部）。
     */
    private List<String> accessibleImageIds() {
        Spec<Image> q = Spec.of();
        if (!LoginTool.isAdmin()) {
            q.or(qq -> {
                qq.isNull("sysOrg.id");
                qq.in("sysOrg.id", LoginTool.getOrgPermissions());
            });
        }
        return imageService.findAll(q, Sort.unsorted()).stream().map(Image::getId).toList();
    }

}
