package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.dto.BuildTestRequest;
import io.github.jiangood.docker.admin.dto.WriteDockerfileRequest;
import io.github.jiangood.docker.admin.service.BuildTestService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 构建测试：以 Git 仓库为上下文，用粘贴的 Dockerfile 覆盖后在默认构建节点本地构建。
 * <p>
 * 构建过程通过 {@code /admin/ws/build-test-log/{logId}} 实时输出日志。
 */
@RestController
@Slf4j
@RequestMapping("admin/build-test")
public class BuildTestController {

    @Resource
    private BuildTestService service;

    /**
     * 读取 Git 仓库中的 Dockerfile 内容，供页面「读取仓库 Dockerfile」按钮回填。
     */
    @HasPermission("build-test:view")
    @RequestMapping("read-dockerfile")
    public AjaxResult readDockerfile(String gitUrl) {
        return AjaxResult.ok().data(service.readDockerfile(gitUrl));
    }

    /**
     * 将编辑后的 Dockerfile 提交并推送到 Git 仓库，返回新提交的短 id。
     */
    @HasPermission("build-test:write")
    @PostMapping("write-dockerfile")
    public AjaxResult writeDockerfile(@RequestBody WriteDockerfileRequest request) {
        String commit = service.writeDockerfile(request.getGitUrl(), request.getDockerfileText(),
                request.getCommitMessage(), request.getBranch());
        return AjaxResult.ok().data(commit).msg("已写入并推送到仓库（提交 " + commit + "）");
    }

    /**
     * 触发构建，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("build-test:build")
    @PostMapping("build")
    public AjaxResult build(@RequestBody BuildTestRequest request) {
        String logId = "build-test-" + RandomUtil.randomString(16);
        service.start(logId, request);
        return AjaxResult.ok().data(logId).msg("构建已开始");
    }

    /**
     * 取消正在运行的构建。
     */
    @HasPermission("build-test:build")
    @PostMapping("cancel")
    public AjaxResult cancel(String logId) {
        Assert.isTrue(service.cancel(logId), "构建任务不存在或已结束");
        return AjaxResult.ok().msg("已发送取消请求");
    }

    /**
     * 当前运行中的构建任务 logId，没有则为空。构建测试不落库，页面刷新后靠它恢复按钮状态。
     */
    @HasPermission("build-test:build")
    @RequestMapping("running")
    public AjaxResult running() {
        return AjaxResult.ok().data(service.getRunningLogId());
    }

}
