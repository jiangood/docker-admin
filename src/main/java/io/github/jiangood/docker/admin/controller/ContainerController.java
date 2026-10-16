package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.dto.ContainerDetailVo;
import io.github.jiangood.docker.admin.dto.ContainerFileVo;
import io.github.jiangood.docker.admin.dto.ContainerFileWriteRequest;
import io.github.jiangood.docker.admin.dto.ContainerSummaryVo;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.util.ContainerPermTool;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping(value = "admin/container")
public class ContainerController {

    @Resource
    private HostService hostService;

    @Resource
    private ContainerService containerService;

    // ------------------------------------------------------------------ 查看

    /** 主机上的容器列表。 */
    @RequestMapping("list")
    public AjaxResult list(String hostId, Boolean all) {
        ContainerPermTool.assertView();
        Host host = requireHost(hostId);
        boolean showAll = all == null || all;
        return AjaxResult.ok().data(containerService.list(host, showAll));
    }

    /** 容器详情（基本信息 + 只读配置）。 */
    @RequestMapping("inspect")
    public AjaxResult inspect(String hostId, String containerId) {
        ContainerPermTool.assertView();
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        return AjaxResult.ok().data(containerService.inspect(host, containerId));
    }

    // ------------------------------------------------------------------ 文件

    /** 列出容器内目录。 */
    @HasPermission("container:file")
    @RequestMapping("files")
    public AjaxResult files(String hostId, String containerId, String path) {
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        List<ContainerFileVo> list = containerService.listFiles(host, containerId, path);
        return AjaxResult.ok().data(list);
    }

    /** 文本预览容器内文件（截断）。 */
    @HasPermission("container:file")
    @RequestMapping("preview")
    public AjaxResult preview(String hostId, String containerId, String path) {
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        Assert.hasText(path, "path 不能为空");
        return AjaxResult.ok().data(containerService.previewFile(host, containerId, path));
    }

    /** 下载容器内文件。 */
    @HasPermission("container:file")
    @RequestMapping("download")
    public void download(String hostId, String containerId, String path, HttpServletResponse response) throws IOException {
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        Assert.hasText(path, "path 不能为空");
        DownloadTool.prepareDownload(response, DownloadTool.fileName(path), "application/octet-stream");
        try (OutputStream out = response.getOutputStream()) {
            containerService.downloadFile(host, containerId, path, out);
            out.flush();
        }
    }

    /** 下载容器内目录（tar 包）。 */
    @HasPermission("container:file")
    @RequestMapping("downloadDir")
    public void downloadDir(String hostId, String containerId, String path, HttpServletResponse response) throws IOException {
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        Assert.hasText(path, "path 不能为空");
        DownloadTool.prepareDownload(response, DownloadTool.fileName(path) + ".tar", "application/x-tar");
        try (OutputStream out = response.getOutputStream()) {
            containerService.downloadDirectory(host, containerId, path, out);
            out.flush();
        }
    }

    /** 上传本地文件到容器内目录（path 为目录，同名文件会被覆盖）。 */
    @HasPermission("container:file")
    @PostMapping("upload")
    public AjaxResult upload(String hostId, String containerId, String path,
                             @RequestParam("file") MultipartFile file) throws IOException {
        Host host = requireHost(hostId);
        Assert.hasText(containerId, "containerId 不能为空");
        Assert.notNull(file, "请选择要上传的文件");
        String raw = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        String filename = slash >= 0 ? raw.substring(slash + 1) : raw;
        Assert.hasText(filename, "文件名不能为空");
        String dir = (path == null || path.isBlank()) ? "/" : path;
        String target = dir.endsWith("/") ? dir + filename : dir + "/" + filename;
        containerService.writeFile(host, containerId, target, file.getBytes());
        return AjaxResult.ok().msg("已上传 " + filename);
    }

    /** 保存容器内文本文件（覆盖写入，用于在线编辑 yml / toml 等）。 */
    @HasPermission("container:file")
    @PostMapping("write")
    public AjaxResult write(@RequestBody ContainerFileWriteRequest request) {
        Host host = requireHost(request.getHostId());
        Assert.hasText(request.getContainerId(), "containerId 不能为空");
        Assert.hasText(request.getPath(), "path 不能为空");
        byte[] content = (request.getContent() == null ? "" : request.getContent()).getBytes(StandardCharsets.UTF_8);
        containerService.writeFile(host, request.getContainerId(), request.getPath(), content);
        return AjaxResult.ok().msg("保存成功");
    }

    // ------------------------------------------------------------------ 操作

    @HasPermission("container:operate")
    @RequestMapping("start")
    public AjaxResult start(String hostId, String containerId) {
        containerService.start(requireHost(hostId), containerId);
        return AjaxResult.ok().msg("启动指令已发送");
    }

    @HasPermission("container:operate")
    @RequestMapping("stop")
    public AjaxResult stop(String hostId, String containerId) {
        containerService.stop(requireHost(hostId), containerId);
        return AjaxResult.ok().msg("停止指令已发送");
    }

    @HasPermission("container:operate")
    @RequestMapping("restart")
    public AjaxResult restart(String hostId, String containerId) {
        containerService.restart(requireHost(hostId), containerId);
        return AjaxResult.ok().msg("重启指令已发送");
    }

    @HasPermission("container:operate")
    @RequestMapping("remove")
    public AjaxResult remove(String hostId, String containerId, Boolean force) {
        containerService.remove(requireHost(hostId), containerId, force == null || force);
        return AjaxResult.ok().msg("删除指令已发送");
    }

    // ------------------------------------------------------------------ 工具

    private Host requireHost(String hostId) {
        Assert.hasText(hostId, "hostId 不能为空");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }
}
