package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;

public interface AppRepository extends BaseRepository<App, String> {

    List<App> findAllByImageUrl(String imageUrl);

    boolean existsByName(String name);

}
