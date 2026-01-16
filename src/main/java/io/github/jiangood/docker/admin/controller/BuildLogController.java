package io.github.jiangood.docker.admin.controller;

import cn.hutool.core.date.DateUtil;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.as.common.dto.AjaxResult;
import io.github.jiangood.as.framework.data.specification.Spec;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;

@RestController
@Slf4j
@RequestMapping(value = "admin/buildLog")
public class BuildLogController {

    @Resource
    private BuildLogService service;

    @RequestMapping("list")
    public AjaxResult list(String projectId, @PageableDefault(sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) throws UnsupportedEncodingException {
        Spec<BuildLog> q = Spec.of();
        q.eq("projectId", projectId);
        Page<BuildLog> page = service.findAll(q, pageable);


        for (BuildLog log : page) {
            log.setLogUrl(LogUrlTool.getLogViewUrl(log.getId()));
            if (log.getTimeSpend() == null) {
                log.setTimeSpend(DateUtil.date().getTime() - log.getCreateTime().getTime());
            }
        }

        return AjaxResult.ok().data( page);
    }



}
