package io.github.jiangood.docker.admin.controller;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Version;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.sdk.engine.DockerClientManager;
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
import org.springframework.web.bind.annotation.*;

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
    private DockerClientManager dockerClientManager;

    @HasPermission("host:list")
    @RequestMapping("page")
    public AjaxResult page(Host request, @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) throws Exception {
        Spec<Host> q = Spec.of();
        q.addExample(request);
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
        try (DockerClient client = dockerClientManager.createClient(input, null)) {
            Version version = client.versionCmd().exec();
            return AjaxResult.ok().msg("连接成功，Docker 版本：" + version.getVersion());
        } catch (Exception e) {
            log.error("测试主机连接失败", e);
            return AjaxResult.err("连接失败：" + e.getMessage());
        }
    }

    @HasPermission("host:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteById(id);
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

    private Host requireHost(String id) {
        org.springframework.util.Assert.hasText(id, "id 不能为空");
        Host host = service.findById(id).orElse(null);
        org.springframework.util.Assert.notNull(host, "主机不存在");
        return host;
    }


    @ExceptionHandler(Exception.class)
    public AjaxResult exception(Exception e){
        log.error(e.getMessage());
        return AjaxResult.err("连接容器引擎失败");
    }

}
