package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostFileService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 主机文件：只保留上传与下载。
 * <p>
 * 路径（path）相对于辅助容器当前挂载的目录，空字符串表示挂载目录本身；
 * 宿主机绝对路径不会出现在接口里，越界访问由
 * {@link io.github.jiangood.docker.admin.util.HostRootPolicy} 拦截。
 */
@RestController
@RequestMapping("admin/host/file")
public class HostFileController {

    @Resource
    private HostService hostService;

    @Resource
    private HostFileService hostFileService;

    /**
     * 下载挂载目录（或其中的子目录），打包成 tar 流。
     */
    @HasPermission("host:file")
    @RequestMapping("downloadDir")
    public void downloadDir(String hostId, String path, HttpServletResponse response) throws IOException {
        Host host = requireHost(hostId);
        String name = DownloadTool.fileName(host.resolveFileRoot());
        DownloadTool.prepareDownload(response, name + ".tar", "application/x-tar");
        try (OutputStream out = response.getOutputStream()) {
            hostFileService.downloadDirectory(host, path, out);
            out.flush();
        }
    }

    /**
     * 上传文件到挂载目录（或其中的子目录），同名文件会被覆盖。
     */
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

    private Host requireHost(String hostId) {
        Assert.hasText(hostId, "hostId 不能为空");
        Host host = hostService.findById(hostId).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }
}
