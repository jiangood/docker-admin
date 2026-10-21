package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.RegistryService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.BusinessException;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.util.dto.Option;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("admin/registry")
public class RegistryController {
    @Resource
    private RegistryService service;

    @HasPermission("registry:view")
    @RequestMapping("info")
    public AjaxResult info() {
        return AjaxResult.ok().data(service.getEffective());
    }

    @HasPermission("registry:view")
    @RequestMapping("options")
    public AjaxResult options() {
        List<Option> options = service.findAll(Sort.by(Sort.Direction.ASC, "url")).stream()
                .map(r -> new Option(r.getId(), r.getUrl() + "/" + r.getNamespace()))
                .collect(Collectors.toList());
        return AjaxResult.ok().data(options);
    }

    @HasPermission("registry:view")
    @RequestMapping("list")
    public AjaxResult list() {
        return AjaxResult.ok().data(service.findAll(Sort.by(Sort.Direction.DESC, "updateTime")));
    }

    /**
     * 查看明文密码：列表接口只返回打码后的密码，这里按需单独读取。
     * 单独用 registry:password 权限，避免能看列表的人都能看到凭据。
     */
    @Log("镜像注册中心-查看密码")
    @HasPermission("registry:password")
    @RequestMapping("password")
    public AjaxResult password(String id) {
        Registry registry = service.findById(id)
                .orElseThrow(() -> new BusinessException("注册中心不存在"));
        return AjaxResult.ok().data(registry.getPassword());
    }

    @Log("镜像注册中心-保存")
    @HasPermission("registry:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody Registry input, @RequestHeader(value = "X-Body-Fields", required = false) List<String> updateFields) {
        service.saveRegistry(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("镜像注册中心-删除")
    @HasPermission("registry:save")
    @PostMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteRegistry(id);
        return AjaxResult.ok().msg("删除成功");
    }
}