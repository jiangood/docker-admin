package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ImageRepository;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 镜像服务：维护 registry.url/namespace/name 形式的镜像。
 */
@Service
@RequiredArgsConstructor
public class ImageService extends BaseService<Image> {

    private final ImageRepository imageRepository;

    public Image findByImageUrl(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        return imageRepository.findByImageUrl(imageUrl);
    }

    /**
     * 按 imageUrl 新增或更新镜像（构建/同步时调用）。
     */
    @Transactional
    public Image upsert(String imageUrl, String name, String source, String registryId, SysOrg sysOrg) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        Image image = imageRepository.findByImageUrl(imageUrl);
        if (image == null) {
            image = new Image();
            image.setImageUrl(imageUrl);
        }
        if (StrUtil.isNotBlank(name)) {
            image.setName(name);
        }
        if (StrUtil.isNotBlank(source)) {
            image.setSource(source);
        }
        if (StrUtil.isNotBlank(registryId)) {
            image.setRegistryId(registryId);
        }
        if (sysOrg != null) {
            image.setSysOrg(sysOrg);
        }
        return imageRepository.save(image);
    }

    @Transactional
    public void deleteByImageUrl(String imageUrl) {
        Image image = findByImageUrl(imageUrl);
        if (image != null) {
            imageRepository.deleteById(image.getId());
        }
    }

}
