package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.Proxy;
import io.github.jiangood.docker.admin.service.ProxyService;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设置菜单「代理管理」接口：列表、下拉选项、保存、删除、排序与连通性测试。
 */
@RestController
@Slf4j
@RequestMapping("admin/proxy")
public class ProxyController {

    @Resource
    private ProxyService service;

    @HasPermission("proxy:view")
    @RequestMapping("list")
    public AjaxResult list() {
        return AjaxResult.ok().data(service.sorted());
    }

    /**
     * 主机表单的代理下拉：只返回名称 / 地址，不返回密码。
     * 不单独要求 proxy:view，能维护主机的人选代理即可；接口仍要求登录。
     */
    @RequestMapping("options")
    public AjaxResult options() {
        return AjaxResult.ok().data(service.options());
    }

    @Log("代理管理-保存")
    @HasPermission("proxy:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody Proxy input,
            @RequestHeader(value = "X-Body-Fields", required = false) List<String> updateFields) {
        service.saveProxy(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("代理管理-删除")
    @HasPermission("proxy:delete")
    @PostMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteProxy(id);
        return AjaxResult.ok().msg("删除成功");
    }

    @HasPermission("proxy:save")
    @PostMapping("move")
    public AjaxResult move(String id, String direction) {
        service.move(id, direction);
        return AjaxResult.ok().msg("排序已更新");
    }

    /**
     * 测试代理连通性：不填目标地址时只测代理本身能否连上；
     * 填了目标（如 {@code www.baidu.com:443}）会再尝试建立隧道，验证认证与转发。
     * <p>
     * 失败也返回 HTTP 200，由前端在弹窗里展示具体原因，避免只弹一个 toast。
     */
    @Log("代理管理-测试")
    @HasPermission("proxy:test")
    @PostMapping("test")
    public AjaxResult test(@RequestBody TestRequest request) {
        return AjaxResult.ok().data(service.test(request.id(), request.type(), request.host(),
                request.port(), request.username(), request.password(), request.target()));
    }

    public record TestRequest(String id, String type, String host, Integer port,
            String username, String password, String target) {
    }
}
