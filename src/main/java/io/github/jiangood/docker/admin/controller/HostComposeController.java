package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.dto.ComposeFileWriteRequest;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostComposeService;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主机详情页的 Compose 管理：查看/编辑 compose 文件、执行 pull / up / down。
 * <p>
 * 平台与主机之间只有 docker API（没有 shell），命令由主机上的临时容器代跑，
 * 具体实现见 {@code DockerApiComposeExecutor}。
 */
@RestController
@RequestMapping("admin/host/compose")
public class HostComposeController {

    @Resource
    private HostService hostService;

    @Resource
    private HostComposeService composeService;

    /**
     * 能力探测：宿主 OS、辅助镜像、daemon 连接、compose 版本。
     */
    @HasPermission("host:compose")
    @RequestMapping("capability")
    public AjaxResult capability(String id) {
        return AjaxResult.ok().data(composeService.capability(requireHost(id)));
    }

    /**
     * 主机上已有的 compose 项目（{@code docker compose ls --all}）。
     */
    @HasPermission("host:compose")
    @RequestMapping("list")
    public AjaxResult list(String id) {
        return AjaxResult.ok().data(composeService.list(requireHost(id)));
    }

    /**
     * 打开指定项目目录（没有登记过的项目用这个入口）。
     */
    @HasPermission("host:compose")
    @RequestMapping("open")
    public AjaxResult open(String id, String dir) {
        return AjaxResult.ok().data(composeService.open(requireHost(id), dir));
    }

    /**
     * 读取文件内容，返回内容 + mtime（保存时用 mtime 做冲突检测）。
     */
    @HasPermission("host:compose")
    @RequestMapping("file")
    public AjaxResult file(String id, String path) {
        return AjaxResult.ok().data(composeService.read(requireHost(id), path));
    }

    /**
     * 项目目录下可编辑的文件列表。
     */
    @HasPermission("host:compose")
    @RequestMapping("files")
    public AjaxResult files(String id, String dir) {
        return AjaxResult.ok().data(composeService.files(requireHost(id), dir));
    }

    /**
     * 保存文件：校验通过才落盘，旧内容备份成 {@code xxx.bak.时间戳}。
     */
    @HasPermission("host:compose")
    @PostMapping("save")
    public AjaxResult save(@RequestBody ComposeFileWriteRequest request) {
        Assert.notNull(request, "请求不能为空");
        Host host = requireHost(request.getHostId());
        String backup = composeService.save(host, request);
        return AjaxResult.ok().msg("保存成功，原文件已备份为 " + backup);
    }

    /**
     * 执行 compose 命令，返回日志标识（前端连日志 WebSocket）。
     */
    @HasPermission("host:compose")
    @PostMapping("run")
    public AjaxResult run(String id, String dir, String action) {
        String logId = composeService.run(requireHost(id), dir, action);
        return AjaxResult.ok().data(logId).msg("已开始执行");
    }

    /**
     * 某项目最近一次执行状态。
     */
    @HasPermission("host:compose")
    @RequestMapping("task")
    public AjaxResult task(String id, String dir) {
        return AjaxResult.ok().data(composeService.task(requireHost(id), dir));
    }

    /**
     * 取消正在执行的任务。
     */
    @HasPermission("host:compose")
    @PostMapping("cancel")
    public AjaxResult cancel(String id, String logId) {
        composeService.cancel(requireHost(id), logId);
        return AjaxResult.ok().msg("已取消");
    }

    /**
     * 把辅助镜像（docker:cli）拉到主机上，返回日志标识。
     */
    @HasPermission("host:compose")
    @PostMapping("prepare")
    public AjaxResult prepare(String id) {
        String logId = composeService.prepare(requireHost(id));
        return AjaxResult.ok().data(logId).msg("开始准备运行环境");
    }

    private Host requireHost(String id) {
        Assert.hasText(id, "id 不能为空");
        Host host = hostService.findById(id).orElse(null);
        Assert.notNull(host, "主机不存在");
        return host;
    }
}
