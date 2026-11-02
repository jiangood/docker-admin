package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.dto.HostFileWriteRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostFileService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.framework.log.Log;
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

/**
 * 主机文件管理。
 * <p>
 * 路径（path/newPath）都是相对于辅助容器挂载目录的相对路径，根目录用空字符串表示；
 * 宿主机绝对路径不会出现在接口里，越界与目录外访问由
 * {@link io.github.jiangood.docker.admin.util.HostRootPolicy} 拦截。
 */
@RestController
@RequestMapping("admin/host/file")
public class HostFileController {

    @Resource
    private HostService hostService;

    @Resource
    private HostFileService hostFileService;

    @HasPermission("host:file")
    @RequestMapping("list")
    public AjaxResult list(String hostId, String path) {
        return AjaxResult.ok().data(hostFileService.list(requireHost(hostId), path));
    }

    @HasPermission("host:file")
    @RequestMapping("preview")
    public AjaxResult preview(String hostId, String path) {
        return AjaxResult.ok().data(hostFileService.preview(requireHost(hostId), path));
    }

    @HasPermission("host:file")
    @RequestMapping("download")
    public void download(String hostId, String path, HttpServletResponse response) throws IOException {
        Host host = requireHost(hostId);
        Assert.hasText(path, "path 不能为空");
        DownloadTool.prepareDownload(response, DownloadTool.fileName(path), "application/octet-stream");
        try (OutputStream out = response.getOutputStream()) {
            hostFileService.download(host, path, out);
            out.flush();
        }
    }

    @HasPermission("host:file")
    @RequestMapping("downloadDir")
    public void downloadDir(String hostId, String path, HttpServletResponse response) throws IOException {
        Host host = requireHost(hostId);
        Assert.hasText(path, "path 不能为空");
        DownloadTool.prepareDownload(response, DownloadTool.fileName(path) + ".tar", "application/x-tar");
        try (OutputStream out = response.getOutputStream()) {
            hostFileService.downloadDirectory(host, path, out);
            out.flush();
        }
    }

    @HasPermission("host:file")
    @PostMapping("upload")
    public AjaxResult upload(String hostId, String path, @RequestParam("file") MultipartFile file) throws IOException {
        Host host = requireHost(hostId);
        Assert.notNull(file, "请选择要上传的文件");
        String raw = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        String filename = slash >= 0 ? raw.substring(slash + 1) : raw;
        Assert.hasText(filename, "文件名不能为空");
        hostFileService.upload(host, path, filename, file.getBytes());
        return AjaxResult.ok().msg("已上传 " + filename);
    }

    @HasPermission("host:file")
    @PostMapping("write")
    public AjaxResult write(@RequestBody HostFileWriteRequest request) {
        Host host = requireHost(request.getHostId());
        Assert.hasText(request.getPath(), "path 不能为空");
        byte[] content = (request.getContent() == null ? "" : request.getContent())
                .getBytes(StandardCharsets.UTF_8);
        hostFileService.write(host, request.getPath(), content);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("主机文件-新建目录")
    @HasPermission("host:file")
    @PostMapping("mkdir")
    public AjaxResult mkdir(String hostId, String path) {
        hostFileService.mkdir(requireHost(hostId), path);
        return AjaxResult.ok().msg("目录已创建");
    }

    @Log("主机文件-重命名")
    @HasPermission("host:file")
    @PostMapping("rename")
    public AjaxResult rename(String hostId, String path, String newPath) {
        hostFileService.rename(requireHost(hostId), path, newPath);
        return AjaxResult.ok().msg("重命名成功");
    }

    @Log("主机文件-删除")
    @HasPermission("host:file")
    @PostMapping("delete")
    public AjaxResult delete(String hostId, String path) {
        hostFileService.delete(requireHost(hostId), path);
        return AjaxResult.ok().msg("删除成功");
    }

    private Host requireHost(String hostId) {
        Assert.hasText(hostId, "hostId 不能为空");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }
}
