package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.SiteMonitor;
import io.github.jiangood.docker.admin.service.SiteMonitorService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.log.Log;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网站监控：监控项 CRUD + 手动立即检测 + 检测历史查询。
 * <p>
 * 定时探测由「网站可达性检查」任务驱动，见 {@code SiteMonitorCheckJob}。
 */
@RestController
@Slf4j
@RequestMapping("admin/site-monitor")
@RequiredArgsConstructor
public class SiteMonitorController {

    private final SiteMonitorService service;

    @HasPermission("site-monitor:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText,
            @PageableDefault(direction = Sort.Direction.DESC, sort = "updateTime") Pageable pageable) {
        Spec<SiteMonitor> q = Spec.of();
        q.orLike(searchText, SiteMonitor.Fields.name, SiteMonitor.Fields.url);
        Page<SiteMonitor> page = service.findAll(q, pageable);
        service.fillNotifyEmailTo(page.getContent());
        return AjaxResult.ok().data(page);
    }

    /**
     * 检测历史（最近在前），页面「历史」抽屉使用。
     */
    @HasPermission("site-monitor:view")
    @RequestMapping("logs")
    public AjaxResult logs(String monitorId,
            @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        return AjaxResult.ok().data(service.pageLogs(monitorId, pageable));
    }

    /**
     * 立即检测一次：同步执行（超时上限即监控项配置的请求超时），返回本次检测记录。
     * 检测结果会同步更新监控项快照与连续失败计数，无论是不是定时触发。
     */
    @Log("网站监控-立即检测")
    @HasPermission("site-monitor:save")
    @RequestMapping("check")
    public AjaxResult check(String id) {
        return AjaxResult.ok().data(service.checkNow(id));
    }

    @Log("网站监控-保存")
    @HasPermission("site-monitor:save")
    @PostMapping("save")
    public AjaxResult save(@RequestBody SiteMonitor input,
            @RequestHeader(value = "X-Body-Fields", required = false) List<String> updateFields) {
        service.saveMonitor(input, updateFields);
        return AjaxResult.ok().msg("保存成功");
    }

    @Log("网站监控-删除")
    @HasPermission("site-monitor:delete")
    @RequestMapping("delete")
    public AjaxResult delete(String id) {
        service.deleteMonitor(id);
        return AjaxResult.ok().msg("删除成功");
    }

}
