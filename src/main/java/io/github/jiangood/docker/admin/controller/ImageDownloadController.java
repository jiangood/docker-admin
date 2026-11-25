package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.dto.ImageDownloadCleanRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadCleanResult;
import io.github.jiangood.docker.admin.dto.ImageDownloadRequest;
import io.github.jiangood.docker.admin.dto.ImageDownloadTaskView;
import io.github.jiangood.docker.admin.service.ImageDownloadService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.log.OperationLogContext;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 镜像下载 tar：在指定主机上拉取公共镜像，并用 {@code docker save} 打成 tar 保存在平台服务器。
 * <p>
 * 每条任务落库，归档按 logId 独立保存；新任务不会清理其他用户的文件。
 * 下载从平台读盘流式转发，文件保留到用户在表格中勾选批量清理。
 * 过程通过 {@code /admin/ws/task-log/{logId}} 实时输出日志。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("admin/image-download")
public class ImageDownloadController {

    private final ImageDownloadService service;

    /** 触发下载，立即返回 logId，供前端打开实时日志。 */
    @Log(value = "镜像下载-开始", detail = "下载镜像「${#request.image}」")
    @HasPermission("image-download:download")
    @PostMapping("download")
    public AjaxResult download(@RequestBody ImageDownloadRequest request) {
        String logId = service.start(request);
        service.download(logId, request);
        return AjaxResult.ok().data(logId).msg("下载已开始");
    }

    /** 下载记录分页，页面 ProTable 使用。 */
    @HasPermission("image-download:view")
    @GetMapping("page")
    public AjaxResult page(String searchText, String status,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "createTime") Pageable pageable) {
        return AjaxResult.ok().data(service.page(searchText, status, pageable));
    }

    /** 单条任务状态：tar 已生成且文件仍存在时 ready=true。 */
    @HasPermission("image-download:view")
    @GetMapping("task")
    public AjaxResult task(String logId) {
        return AjaxResult.ok().data(service.taskView(logId));
    }

    /** 下载已生成的 tar：从平台读盘流式转发，不删除源文件。 */
    @HasPermission("image-download:download")
    @GetMapping("tar")
    public void tar(String logId, HttpServletResponse response) throws Exception {
        ImageDownloadTaskView task = service.reserveDownload(logId);
        try {
            DownloadTool.prepareDownload(response, task.getFileName(), "application/x-tar");
            if (task.getSize() > 0) {
                response.setContentLengthLong(task.getSize());
            }
            service.copyTarTo(logId, response.getOutputStream());
        } finally {
            service.releaseDownload(logId);
        }
    }

    /** 批量清理选中的 tar；记录保留并标记为已清理，运行中的任务会跳过。 */
    @Log("镜像下载-批量清理")
    @HasPermission("image-download:download")
    @PostMapping("clean")
    public AjaxResult clean(@Valid @RequestBody ImageDownloadCleanRequest request) {
        OperationLogContext.setDetail("清理镜像归档 " + request.getLogIds().size() + " 个");
        ImageDownloadCleanResult result = service.clean(request.getLogIds());
        String message = "已清理 " + result.getCleaned() + " 个归档";
        if (result.getSkipped() > 0) {
            message += "，跳过 " + result.getSkipped() + " 个";
        }
        return AjaxResult.ok().data(result).msg(message);
    }

}
