package io.github.jiangood.docker.admin.controller;

import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.HostImageExportTaskView;
import io.github.jiangood.docker.admin.proxy.ProxySettings;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostImageExportService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.service.HostUsageService;
import io.github.jiangood.docker.admin.util.ContainerPermTool;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("admin/host")
public class HostController  {


    @Resource
    private HostService service;

    @Resource
    private io.github.jiangood.docker.admin.service.HostDockerService hostDockerService;

    @Resource
    private HostImageExportService hostImageExportService;

    @Resource
    private HostUsageService hostUsageService;

    @HasPermission("host:list")
    @RequestMapping("page")
    public AjaxResult page(Host request, String searchText, @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) throws Exception {
        Spec<Host> q = Spec.of();
        q.addExample(request);
        // 列表页顶部搜索框：名称 / IP / 连接地址 任一命中即可
        q.orLike(searchText, Host.Fields.name, Host.Fields.ip, Host.Fields.dockerHost, Host.Fields.sshHost);
        Page<Host> page = service.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    @HasPermission("host:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody Host input, @RequestHeader("X-Body-Fields") List<String> updateFields) throws Exception {
        service.saveHost(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }

    /**
     * 测试主机连通性。
     * <p>
     * 用的就是请求体里（表单上）的连接配置，只有两个密码是例外：SSH 密码与代理密码不回传前端，
     * 界面上留空或仍是 {@code ***} 时按 {@link HostService#fillSshPassword} /
     * {@link HostService#fillProxyPassword} 从库里沿用旧值。
     * <p>
     * 结果提示里带上实际测试的地址（以及是否沿用了旧密码），便于确认测的是表单里填的配置，
     * 而不是库里保存的那份。
     */
    @HasPermission("host:save")
    @PostMapping("test")
    public AjaxResult test(@RequestBody Host input) {
        service.normalize(input);
        // 在回填密码之前判断，用来在提示里说明密码来源
        boolean sshPasswordReused = input.isSsh()
                && StrUtil.isBlank(input.getSshPassword())
                && StrUtil.isNotBlank(input.getId());
        boolean proxyPasswordReused = proxyPasswordReused(input);
        service.fillSshPassword(input);
        service.fillProxyPassword(input);

        String target = describeTarget(input);
        StringBuilder notes = new StringBuilder();
        if (sshPasswordReused) {
            notes.append("，SSH 密码沿用已保存的值");
        }
        if (proxyPasswordReused) {
            notes.append("，代理密码沿用已保存的值");
        }

        log.info("测试主机连接：{}{}", target, notes);
        try {
            String version = hostDockerService.dockerVersion(input);
            return AjaxResult.ok().msg("连接成功（" + target + notes + "），Docker 版本：" + version);
        } catch (Exception e) {
            log.error("测试主机连接失败：{}{}", target, notes, e);
            String reason = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            return AjaxResult.err("连接失败（" + target + notes + "）：" + reason);
        }
    }

    /**
     * 本次测试的目标地址（不含密码），写进结果提示里。
     */
    private static String describeTarget(Host host) {
        String type = host.getConnectionType();
        if (Host.TYPE_SSH.equalsIgnoreCase(type)) {
            String user = StrUtil.blankToDefault(host.getSshUser(), "root");
            String sshHost = StrUtil.blankToDefault(host.getSshHost(), host.getDockerHost());
            int port = host.getSshPort() == null ? 22 : host.getSshPort();
            return "ssh://" + user + "@" + sshHost + ":" + port;
        }
        if (Host.TYPE_TCP.equalsIgnoreCase(type)) {
            String target = StrUtil.blankToDefault(host.getDockerHost(), "远程 TCP（未填写地址）");
            ProxySettings proxy = proxySettingsOf(host);
            return proxy.enabled() ? target + "（经代理 " + proxy.displayUrl() + "）" : target;
        }
        return StrUtil.blankToDefault(host.getDockerHost(), "本机默认端点");
    }

    /**
     * 代理密码是否会在回填时从库里沿用（界面只回显 {@code ***}）。
     */
    private static boolean proxyPasswordReused(Host host) {
        return Host.TYPE_TCP.equalsIgnoreCase(host.getConnectionType())
                && StrUtil.isNotBlank(host.getId())
                && ProxySettings.PASSWORD_MASK.equals(proxySettingsOf(host).password());
    }

    private static ProxySettings proxySettingsOf(Host host) {
        return ProxySettings.of(host.getProxyType(), host.getProxyHost(), host.getProxyPort(),
                host.getProxyUsername(), host.getProxyPassword());
    }

    @HasPermission("host:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteHost(id);
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("host:list")
    @RequestMapping("options")
    public AjaxResult options(@RequestParam(defaultValue = "false") boolean onlyRunner, String searchText) {
        Spec<Host> q = Spec.of();
        if (onlyRunner) {
            q.eq(Host.Fields.isRunner, true);
        }
        q.orLike(searchText, Host.Fields.name, Host.Fields.dockerHost, Host.Fields.sshHost);
        List<Host> list = service.findAll(q, Sort.by(Host.Fields.name));
        List<Option> options = new ArrayList<>();
        for (Host h : list) {
            if (onlyRunner && !h.getIsRunner()) {
                continue;
            }
            options.add(new Option(h.getId(), h.getName()));
        }
        return AjaxResult.ok().data(options);
    }

    /**
     * 默认构建主机（全局设置）的 id，供「立即构建」弹窗默认选中；未配置时为 null。
     */
    @HasPermission("host:list")
    @RequestMapping("default")
    public AjaxResult defaultHost() {
        Host host = service.getDefaultDockerRunner();
        return AjaxResult.ok().data(host == null ? null : host.getId());
    }

    /**
     * 主机 Docker 引擎信息（详情页头部）。
     */
    @HasPermission("host:list")
    @RequestMapping("info")
    public AjaxResult info(String id) {
        Host host = requireHost(id);
        return AjaxResult.ok().data(hostDockerService.info(host));
    }

    /**
     * 主机基础信息。
     */
    @HasPermission("host:list")
    @RequestMapping("get")
    public AjaxResult get(String id) {
        return AjaxResult.ok().data(requireHost(id));
    }

    /**
     * 主机实时用量（等价于 {@code docker stats}）：主机级汇总 + 逐容器明细。
     * <p>
     * 权限与容器列表一致（{@code host:list} 或 {@code app:view}），因为明细里就是容器名与用量。
     *
     * @param refresh 为 true 时忽略 3 秒缓存强制重新采集
     */
    @RequestMapping("usage")
    public AjaxResult usage(String id, @RequestParam(defaultValue = "false") boolean refresh) {
        ContainerPermTool.assertView();
        return AjaxResult.ok().data(hostUsageService.usage(id, refresh));
    }

    /**
     * 主机上的镜像列表。
     */
    @HasPermission("host:list")
    @RequestMapping("images")
    public AjaxResult images(String id) {
        Host host = requireHost(id);
        return AjaxResult.ok().data(hostDockerService.listImages(host));
    }

    /**
     * 导出主机上的镜像为 tar：后台在该主机上执行 {@code docker save}，立即返回 logId。
     * <p>
     * 进度通过 {@code /admin/ws/host-image-export-log/{logId}} 实时查看；
     * 完成后用 {@code exportImage/download} 下载，tar 仍保留在主机上。
     */
    @HasPermission("host:image")
    @PostMapping("exportImage/start")
    public AjaxResult exportImageStart(String id, String image) {
        Host host = requireHost(id);
        String logId = hostImageExportService.register(host, image);
        hostImageExportService.export(logId, host.getId(), image);
        return AjaxResult.ok().data(logId).msg("开始导出");
    }

    /**
     * 某主机上某个镜像最近一次的导出状态；从未导出过时 data 为 null。
     */
    @HasPermission("host:image")
    @RequestMapping("exportImage/status")
    public AjaxResult exportImageStatus(String id, String image) {
        return AjaxResult.ok().data(hostImageExportService.taskView(id, image));
    }

    /**
     * 下载已导出的 tar：SSH 主机经 SFTP 流式读取，本机 / TCP 主机直接读平台文件。
     * <p>
     * 不删除主机上的源文件，用户可继续用弹窗里显示的主机路径取用或自行清理。
     */
    @HasPermission("host:image")
    @RequestMapping("exportImage/download")
    public void exportImageDownload(String logId, HttpServletResponse response) throws Exception {
        HostImageExportTaskView task = hostImageExportService.readyTask(logId);
        DownloadTool.prepareDownload(response, task.getFileName(), "application/x-tar");
        if (task.getSize() > 0) {
            response.setContentLengthLong(task.getSize());
        }
        hostImageExportService.copyTarTo(logId, response.getOutputStream());
    }

    /**
     * 删除镜像。{@code image} 为 {@code repo:tag} 时删除单个标签，为镜像 ID 时删除整个镜像。
     * <p>
     * 404 / 409 在此就地转换：交由全局处理器只能得到笼统提示，这里给出更具体的说明。
     */
    @HasPermission("host:image")
    @RequestMapping("removeImage")
    public AjaxResult removeImage(String id, String image) {
        Host host = requireHost(id);
        org.springframework.util.Assert.hasText(image, "image 不能为空");
        try {
            hostDockerService.removeImage(host, image);
            return AjaxResult.ok().msg("删除成功");
        } catch (ConflictException e) {
            log.warn("镜像被占用，删除失败：{}", image);
            return AjaxResult.err("该镜像被容器占用，无法删除");
        } catch (NotFoundException e) {
            log.warn("镜像不存在：{}", image);
            return AjaxResult.err("镜像不存在");
        }
    }

    /**
     * 清理悬空（无标签）镜像，返回回收的字节数。
     */
    @HasPermission("host:image")
    @RequestMapping("pruneImages")
    public AjaxResult pruneImages(String id) {
        Host host = requireHost(id);
        long reclaimed = hostDockerService.pruneImages(host);
        return AjaxResult.ok().msg("已清理悬空镜像，回收空间 " + formatBytes(reclaimed)).data(reclaimed);
    }

    /**
     * 导入 tar 镜像到该主机：把 {@code docker save} 归档直接载入主机 docker，不推送到注册中心。
     * <p>
     * 归档大小常见几 GB，上传与载入在同一请求内完成（远程主机经 SSH 传输），
     * 因此前端需要提示「请稍候」，不要中途刷新页面。
     */
    @HasPermission("host:image")
    @PostMapping("loadImage")
    public AjaxResult loadImage(String id, @RequestParam("file") MultipartFile file) throws IOException {
        Host host = requireHost(id);
        Assert.notNull(file, "请选择要导入的 tar 包");
        Assert.state(!file.isEmpty(), "tar 包为空，无法导入");

        String raw = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        String filename = raw.substring(raw.lastIndexOf('/') + 1);
        Assert.state(StrUtil.endWithIgnoreCase(filename, ".tar"),
                "仅支持 docker save 生成的 .tar 归档");

        List<String> imported;
        try (InputStream in = file.getInputStream()) {
            imported = hostDockerService.loadImage(host, in);
        }
        String msg = imported.isEmpty()
                ? "已载入镜像归档"
                : "已载入 " + imported.size() + " 个镜像";
        return AjaxResult.ok().msg(msg).data(imported);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }

    private Host requireHost(String id) {
        org.springframework.util.Assert.hasText(id, "id 不能为空");
        Host host = service.findById(id).orElse(null);
        org.springframework.util.Assert.notNull(host, "主机不存在");
        return host;
    }

}
