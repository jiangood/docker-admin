package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.dto.BuildTestRequest;
import io.github.jiangood.docker.admin.service.BuildTestService;
import io.github.jiangood.openadmin.framework.perm.HasPermission;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
     * 触发构建，立即返回 logId，供前端打开实时日志。
     */
    @HasPermission("build-test:build")
    @PostMapping("build")
    public AjaxResult build(@RequestBody BuildTestRequest request) {
        String logId = "build-test-" + RandomUtil.randomString(16);
        service.build(logId, request);
        return AjaxResult.ok().data(logId).msg("构建已开始");
    }

}
