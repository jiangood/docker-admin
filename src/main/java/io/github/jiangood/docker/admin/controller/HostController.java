package io.github.jiangood.docker.admin.controller;

import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import jakarta.annotation.Resource;
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
     */
    @HasPermission("host:save")
    @PostMapping("test")
    public AjaxResult test(@RequestBody Host input) {
        service.normalize(input);
        service.fillSshPassword(input);
        try {
            String version = hostDockerService.dockerVersion(input);
            return AjaxResult.ok().msg("连接成功，Docker 版本：" + version);
        } catch (Exception e) {
            log.error("测试主机连接失败", e);
            return AjaxResult.err("连接失败：" + e.getMessage());
        }
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
     * 主机上的镜像列表。
     */
    @HasPermission("host:list")
    @RequestMapping("images")
    public AjaxResult images(String id) {
        Host host = requireHost(id);
        return AjaxResult.ok().data(hostDockerService.listImages(host));
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
