package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.BuildLogRepository;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuildLogService extends BaseService<BuildLog> {

    private final BuildLogRepository buildLogRepository;

    public List<String> versions(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        q.eq(BuildLog.Fields.success, true);
        return distinctTagsDesc(buildLogRepository.findAll(q));
    }

    /**
     * 某个镜像地址下所有构建成功的版本（tag），倒序。
     */
    public List<String> versionsByImageUrl(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return Collections.emptyList();
        }
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.imageUrl, imageUrl);
        q.eq(BuildLog.Fields.success, true);
        return distinctTagsDesc(buildLogRepository.findAll(q));
    }

    private List<String> distinctTagsDesc(List<BuildLog> list) {
        return list.stream()
                .map(BuildLog::getTag)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
    }

    @Transactional
    public BuildLog saveLog(BuildLog buildLog) {
        return buildLogRepository.saveAndFlush(buildLog);
    }

    public List<BuildLog> findByProject(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        return buildLogRepository.findAll(q);
    }

    @Transactional
    public void cleanErrorLog(String projectId) {
        buildLogRepository.deleteErrorLogsByProjectId(projectId);
    }

    public List<BuildLog> findByProjectProcessing(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        q.isNull(BuildLog.Fields.success);
        return buildLogRepository.findAll(q);
    }
}
