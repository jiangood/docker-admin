package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.ImageVersion;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

import java.util.List;
import java.util.Optional;

public interface ImageVersionRepository extends BaseRepository<ImageVersion, String> {

    List<ImageVersion> findAllByImageUrl(String imageUrl);

    Optional<ImageVersion> findByImageUrlAndTag(String imageUrl, String tag);

    void deleteByImageUrl(String imageUrl);

}
