package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.dto.ContainerDetailVo;
import io.github.jiangood.docker.admin.dto.ContainerFileVo;
import io.github.jiangood.docker.admin.dto.ContainerSummaryVo;
import io.github.jiangood.docker.admin.service.ContainerService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.util.ContainerPermTool;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping(value = "admin/container")
public class ContainerController {

    @Resource
    private HostService hostService;

    @Resource
    private DockerClientManager dockerClientManager;

    @Resource
    private ContainerService containerService;

    /**
     * 兼容旧接口：按应用标签查询容器状态。
     */
    @HasPermission("app:view")
    @RequestMapping("status")
    public AjaxResult status(String hostId, String appName, String containerId) {
        log.debug("查询容器状态:{}", appName);
        DockerClient cli = null;
        try {
            Host host = hostService.findById(hostId).orElse(null);
            cli = dockerClientManager.getClient(host);

            if (containerId != null) {
                InspectContainerResponse res = cli.inspectContainerCmd(containerId).exec();
                return AjaxResult.ok().data(res.getState().getStatus());
            }

            ListContainersCmd cmd = cli.listContainersCmd();
            if (appName != null) {
                Map<String, String> appLabelFilter = dockerClientManager.getAppLabelFilter(appName);
                cmd.withLabelFilter(appLabelFilter);
            }

            List<Container> list = cmd.withShowAll(true).exec();
            if (list.isEmpty()) {
                return AjaxResult.ok().data("未知");
            }
            return AjaxResult.ok().data(list.get(0).getStatus());
        } catch (Exception e) {
            log.warn("查询容器状态失败: {}", e.getMessage());
            return AjaxResult.ok().data("未知");
        } finally {
            IOUtils.closeQuietly(cli);
        }
    }

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
        prepareDownload(response, fileName(path), "application/octet-stream");
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
        prepareDownload(response, fileName(path) + ".tar", "application/x-tar");
        try (OutputStream out = response.getOutputStream()) {
            containerService.downloadDirectory(host, containerId, path, out);
            out.flush();
        }
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

    private static void prepareDownload(HttpServletResponse response, String fileName, String contentType) {
        response.setContentType(contentType);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    private static String fileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "download";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        String name = idx >= 0 ? p.substring(idx + 1) : p;
        return StrUtil.blankToDefault(name, "download");
    }
}
