package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.AppGroupDao;
import io.github.jiangood.docker.admin.entity.AppGroup;
import io.github.jiangood.as.framework.data.service.BaseService;
import io.github.jiangood.as.framework.data.specification.Spec;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppGroupService extends BaseService<AppGroup> {

    @Resource
    private AppGroupDao appGroupDao;


    public List<AppGroup> findAll(Spec<AppGroup> q, Sort sort) {
        return appGroupDao.findAll(q,sort);
    }
}

