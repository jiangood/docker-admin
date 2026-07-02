package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppRepository extends BaseRepository<App, String> {

    List<App> findAllByImageUrl(String imageUrl);


}
