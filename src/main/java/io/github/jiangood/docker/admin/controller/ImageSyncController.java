package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.dto.ImageSyncRequest;
import io.github.jiangood.docker.admin.service.ImageSyncService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 镜像同步：选择网络通畅的主机，拉取公共镜像并推送到注册中心。
 * <p>
 * 同步过程通过 {@code /admin/ws/sync-log/{logId}} 实时输出日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/image-sync")
public class ImageSyncController {

    @Resource
    private ImageSyncService service;

    /**
     * 触发同步，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("image-sync:sync")
    @PostMapping("sync")
    public AjaxResult sync(@RequestBody ImageSyncRequest request) {
        String logId = "sync-" + RandomUtil.randomString(16);
        service.sync(logId, request);
        return AjaxResult.ok().data(logId).msg("同步已开始");
    }

}
