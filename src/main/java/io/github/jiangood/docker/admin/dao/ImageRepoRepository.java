package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.ImageRepo;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface ImageRepoRepository extends BaseRepository<ImageRepo, String> {

    ImageRepo findByImageUrl(String imageUrl);

}
