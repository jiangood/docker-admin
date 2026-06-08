package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.BuildLogRepository;
import io.github.jiangood.docker.admin.entity.BuildLog;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BuildLogService extends BaseService<BuildLog> {

    private final BuildLogRepository buildLogRepository;

    public BuildLogService(BuildLogRepository buildLogRepository) {
        super(buildLogRepository);
        this.buildLogRepository = buildLogRepository;
    }

    public List<String> versions(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        q.eq(BuildLog.Fields.success, true);
        List<BuildLog> list = buildLogRepository.findAll(q);
        List<String> versions = list.stream().map(BuildLog::getVersion).distinct().collect(Collectors.toList());
        Collections.sort(versions);
        Collections.reverse(versions);
        return versions;
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
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        q.eq(BuildLog.Fields.success, false);
        List<BuildLog> list = buildLogRepository.findAll(q);
        buildLogRepository.deleteAll(list);
    }

    public List<BuildLog> findByProjectProcessing(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        q.isNull(BuildLog.Fields.success);
        return buildLogRepository.findAll(q);
    }

    public BuildLog findTop1ByProject(String projectId) {
        Spec<BuildLog> q = Spec.of();
        q.eq(BuildLog.Fields.projectId, projectId);
        return buildLogRepository.findTop1(q, Sort.by("createTime"));
    }
}
