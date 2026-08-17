package io.github.jiangood.docker.admin.controller;

import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.docker.admin.service.BuildLogService;
import io.github.jiangood.openadmin.util.dto.AjaxResult;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.LocalDateTime;

@RestController
@RequestMapping("admin/home")
public class HomeController {

    @Resource
    BuildLogService buildLogService;

    @RequestMapping("buildingPage")
    public AjaxResult buildingPage(@PageableDefault(direction = Sort.Direction.DESC,sort = "createTime") Pageable pageable) throws UnsupportedEncodingException {
        Spec<BuildLog> q = Spec.of();
        q.isNull(BuildLog.Fields.success);

        Page<BuildLog> page = buildLogService.findAll(q,pageable);

        for (BuildLog log : page) {
            log.setLogUrl(LogUrlTool.getLogViewUrl(log.getId()));
            if (log.getTimeSpend() == null) {
                log.setTimeSpend(Duration.between(log.getCreateTime(), LocalDateTime.now()).toMillis());
            }
        }


        return AjaxResult.ok().data(page);

    }
}
