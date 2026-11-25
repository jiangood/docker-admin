package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadTaskView;
import io.github.jiangood.openadmin.modules.task.entity.SysTaskLog;
import io.github.jiangood.docker.admin.service.ImageDownloadService;
import io.github.jiangood.openadmin.modules.task.service.SysTaskLogService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 镜像下载 tar：在指定主机上拉取公共镜像，并用 {@code docker save} 打成 tar 保存在平台服务器。
 * <p>
 * 下载时直接从平台读盘流式转发，不再区分主机连接方式；可勾选下载完成后删除以节省磁盘。
 * 过程通过 {@code /admin/ws/task-log/{logId}} 实时输出日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/image-download")
public class ImageDownloadController {

    @Resource
    private ImageDownloadService service;

    @Resource
    private SysTaskLogService taskLogService;

    /**
     * 触发下载，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("image-download:download")
    @PostMapping("download")
    public AjaxResult download(@RequestBody ImageDownloadRequest request) {
        // 参数校验同步执行，便于把错误直接返回给前端
        service.validate(request);
        String logId = "image-download-" + RandomUtil.randomString(16);
        taskLogService.start(logId, SysTaskLog.TYPE_IMAGE_DOWNLOAD,
                "下载镜像 " + StrUtil.sub(request.getImage(), 0, 120),
                "image-download:download", taskLogService.currentTriggerUser());
        service.download(logId, request);
        return AjaxResult.ok().data(logId).msg("下载已开始");
    }

    /**
     * 任务结果：tar 已生成时返回文件名、大小与保存位置；执行中或失败时 data 为 null。
     */
    @HasPermission("image-download:download")
    @RequestMapping("task")
    public AjaxResult task(String logId) {
        return AjaxResult.ok().data(service.taskView(logId));
    }

    /**
     * 下载已生成的 tar：从平台读盘流式转发，不删除源文件；{@code deleteAfter=true} 时
     * 在响应写完后删除该 tar（用于大镜像省磁盘）。
     */
    @HasPermission("image-download:download")
    @RequestMapping("tar")
    public void tar(String logId, Boolean deleteAfter, HttpServletResponse response) throws Exception {
        ImageDownloadTaskView task = service.readyTask(logId);
        DownloadTool.prepareDownload(response, task.getFileName(), "application/x-tar");
        if (task.getSize() > 0) {
            response.setContentLengthLong(task.getSize());
        }
        service.copyTarTo(logId, response.getOutputStream());
        if (Boolean.TRUE.equals(deleteAfter)) {
            service.deleteTarQuietly(logId);
        }
    }

    /**
     * 删除已生成的 tar 与登记条目，给用户腾磁盘用。
     */
    @HasPermission("image-download:download")
    @PostMapping("delete")
    public AjaxResult delete(String logId) {
        service.deleteTar(logId);
        return AjaxResult.ok().msg("已删除 tar");
    }

}
