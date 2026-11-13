package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.TaskLog;
import io.github.jiangood.docker.admin.service.TaskLogService;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务中心：统一查看后台长任务（Agent 部署、镜像同步、构建等）的状态与实时日志。
 * <p>
 * 日志正文不在这里，前端按 logId 连 {@code /admin/ws/task-log/{logId}}；
 * 握手鉴权按任务记录的 {@code permission} 字段，本控制器的 {@code task:view}
 * 只控制列表页入口。
 */
@RestController
@RequestMapping("admin/task")
public class TaskController {

    @Resource
    private TaskLogService taskLogService;

    @Resource
    private TaskLogRegistry taskLogRegistry;

    /**
     * 任务记录（最新在前）。searchText 按标题模糊搜索，status / type 精确过滤。
     */
    @HasPermission("task:view")
    @RequestMapping("page")
    public AjaxResult page(String searchText, String status, String type,
                           @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        Spec<TaskLog> q = Spec.of();
        q.orLike(searchText, TaskLog.Fields.title);
        if (StrUtil.isNotBlank(status)) {
            q.eq(TaskLog.Fields.status, status);
        }
        if (StrUtil.isNotBlank(type)) {
            q.eq(TaskLog.Fields.type, type);
        }
        Page<TaskLog> page = taskLogService.findAll(q, pageable);
        return AjaxResult.ok().data(page);
    }

    /**
     * 取消运行中的任务：关闭任务登记的可中断资源（如 docker 拉取流），
     * 任务线程随后自行失败并补全记录。当前阶段不可中断（如克隆代码）时返回 false。
     */
    @HasPermission("task:cancel")
    @PostMapping("cancel")
    public AjaxResult cancel(String logId) {
        Assert.hasText(logId, "logId 不能为空");
        TaskLog task = taskLogService.findByLogId(logId);
        Assert.notNull(task, "任务不存在");
        Assert.isTrue(task.isRunning(), "任务已结束");
        Assert.isTrue(taskLogRegistry.cancel(logId), "任务当前阶段不可取消，请稍后再试");
        return AjaxResult.ok().msg("已请求取消");
    }
}
