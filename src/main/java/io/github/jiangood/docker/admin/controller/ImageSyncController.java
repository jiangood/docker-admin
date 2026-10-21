package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.dto.ImageSyncRequest;
import io.github.jiangood.docker.admin.entity.ImageSyncLog;
import io.github.jiangood.docker.admin.service.ImageSyncLogService;
import io.github.jiangood.docker.admin.service.ImageSyncService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 镜像同步：选择网络通畅的主机，拉取公共镜像并推送到注册中心，
 * 目标主机（可选）再从注册中心拉取。
 * <p>
 * 同步过程通过 {@code /admin/ws/sync-log/{logId}} 实时输出日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/image-sync")
public class ImageSyncController {

    @Resource
    private ImageSyncService service;

    @Resource
    private ImageSyncLogService syncLogService;

    /**
     * 同步记录（最新在前），页面表格展示；searchText 按镜像地址与主机名模糊搜索。
     */
    @HasPermission("image-sync:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText, @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        Spec<ImageSyncLog> q = Spec.of();
        q.orLike(searchText, ImageSyncLog.Fields.sourceImage, ImageSyncLog.Fields.targetImage,
                ImageSyncLog.Fields.hostName, ImageSyncLog.Fields.targetHostName);
        Page<ImageSyncLog> page = syncLogService.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    /**
     * 触发同步，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("image-sync:sync")
    @PostMapping("sync")
    public AjaxResult sync(@RequestBody ImageSyncRequest request) {
        // 参数校验同步执行，便于把错误直接返回给前端
        service.validate(request);
        String logId = "sync-" + RandomUtil.randomString(16);
        // 先落库记录再起异步任务：接口返回时「同步中」这条已经能在列表里看到
        ImageSyncLog syncLog = service.startRecord(logId, request);
        service.sync(logId, request, syncLog);
        return AjaxResult.ok().data(logId).msg("同步已开始");
    }

}
