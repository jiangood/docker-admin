package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.sa.framework.data.repository.BaseDao;
import io.github.jiangood.sa.framework.data.specification.Spec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectDao extends BaseDao<Project> {


    public Page<Project> findByNameLike(String searchText, Pageable pageable) {
        Spec<Project> q= Spec.of();
        q.like("name", searchText);
        return findAll(q, pageable);
    }
}
