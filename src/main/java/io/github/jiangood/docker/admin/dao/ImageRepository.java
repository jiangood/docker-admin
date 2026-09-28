package io.github.jiangood.docker.admin.dao;

import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.openadmin.framework.data.BaseRepository;

public interface ImageRepository extends BaseRepository<Image, String> {

    Image findByWebhookToken(String webhookToken);

}
