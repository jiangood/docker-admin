package io.github.jiangood.docker.admin.dao;


import io.github.jiangood.as.framework.data.specification.Spec;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.as.framework.data.repository.BaseDao;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AppDao extends BaseDao<App> {

    public List<App> findByTagIsNull(){
        Spec<App> spec = Spec.of();
        spec.isNull(App.Fields.tag);
        return findAll(spec);
    }
}
