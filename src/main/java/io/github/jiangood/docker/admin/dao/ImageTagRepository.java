package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.ImageTag;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;
import java.util.Optional;

public interface ImageTagRepository extends BaseRepository<ImageTag, String> {

    List<ImageTag> findAllByImageUrl(String imageUrl);

    Optional<ImageTag> findByImageUrlAndTag(String imageUrl, String tag);

    void deleteByImageUrl(String imageUrl);

}
