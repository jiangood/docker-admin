package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.service.ImageDownloadService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 镜像下载 tar：在指定主机上拉取公共镜像，并打成 {@code docker save} 归档留在该主机。
 * <p>
 * 过程通过 {@code /admin/ws/image-download-log/{logId}} 实时输出日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/image-download")
public class ImageDownloadController {

    @Resource
    private ImageDownloadService service;

    /**
     * 触发下载，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("image-download:download")
    @PostMapping("download")
    public AjaxResult download(@RequestBody ImageDownloadRequest request) {
        // 参数校验同步执行，便于把错误直接返回给前端
        service.validate(request);
        String logId = "image-download-" + RandomUtil.randomString(16);
        service.download(logId, request);
        return AjaxResult.ok().data(logId).msg("下载已开始");
    }

}
