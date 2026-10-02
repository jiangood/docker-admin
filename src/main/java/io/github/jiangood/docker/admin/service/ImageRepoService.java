package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ImageRepoRepository;
import io.github.jiangood.docker.admin.entity.ImageRepo;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 镜像仓库服务：维护 registry.url/namespace/name 形式的镜像仓库。
 */
@Service
@RequiredArgsConstructor
public class ImageRepoService extends BaseService<ImageRepo> {

    private final ImageRepoRepository imageRepoRepository;

    public ImageRepo findByImageUrl(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        return imageRepoRepository.findByImageUrl(imageUrl);
    }

    /**
     * 按 imageUrl 新增或更新镜像仓库（构建/同步时调用）。
     */
    @Transactional
    public ImageRepo upsert(String imageUrl, String name, String source, String registryId, SysOrg sysOrg) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        ImageRepo repo = imageRepoRepository.findByImageUrl(imageUrl);
        if (repo == null) {
            repo = new ImageRepo();
            repo.setImageUrl(imageUrl);
        }
        if (StrUtil.isNotBlank(name)) {
            repo.setName(name);
        }
        if (StrUtil.isNotBlank(source)) {
            repo.setSource(source);
        }
        if (StrUtil.isNotBlank(registryId)) {
            repo.setRegistryId(registryId);
        }
        if (sysOrg != null) {
            repo.setSysOrg(sysOrg);
        }
        return imageRepoRepository.save(repo);
    }

    @Transactional
    public void deleteByImageUrl(String imageUrl) {
        ImageRepo repo = findByImageUrl(imageUrl);
        if (repo != null) {
            imageRepoRepository.deleteById(repo.getId());
        }
    }

}
