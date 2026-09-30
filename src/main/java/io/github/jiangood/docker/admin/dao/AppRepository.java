package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;

public interface AppRepository extends BaseRepository<App, String> {

    List<App> findAllByImage_Id(String imageId);

    List<App> findAllByHost_Id(String hostId);

    boolean existsByName(String name);

}
