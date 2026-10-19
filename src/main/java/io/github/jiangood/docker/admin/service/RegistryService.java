package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ImageRepository;
import io.github.jiangood.docker.admin.dao.ProjectRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.entity.Project;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.framework.data.specification.Spec;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService extends BaseService<Registry> {
    private final RegistryRepository registryRepository;
    private final ProjectRepository projectRepository;
    private final ImageRepository imageRepository;
    private final SystemConfigService systemConfigService;

    /**
     * 按镜像地址解析它所属的注册中心，用于拉取镜像时附带凭据。
     * <p>
     * 多注册中心并存时不能只看系统默认值：镜像属于哪个注册中心由镜像地址本身决定，
     * 否则默认值指向另一个注册中心时，私有镜像会被匿名拉取而报
     * {@code pull access denied}（这也是「加了第二个注册中心后原本正常的部署开始 404」的原因）。
     * 依次尝试：
     * <ol>
     *   <li>镜像表登记的 {@code registryId}（平台构建/同步时记下的归属，最准确）；</li>
     *   <li>同一 host 下按「url + namespace」做最长前缀匹配——一个 host 上可以有多个命名空间/账号；</li>
     *   <li>前缀也对不上时优先系统默认值，再退回该 host 上的任意一个注册中心。</li>
     * </ol>
     * 镜像没有显式注册中心（Docker Hub 公共镜像）或没有任何注册中心匹配时返回 null，调用方匿名拉取。
     */
    public Registry forImageUrl(String imageUrl) {
        String repoUrl = ImageUrlUtils.repoOf(imageUrl);
        if (StrUtil.isBlank(repoUrl)) {
            return null;
        }
        String imageHost = ImageUrlUtils.hostOf(repoUrl);

        // 1) 镜像表登记的归属。仅当注册中心 host 与镜像地址一致时才沿用，
        //    避免镜像地址被改到别处后仍带着旧注册中心的账号密码。
        Image image = imageRepository.findByImageUrl(repoUrl);
        if (image != null && StrUtil.isNotBlank(image.getRegistryId())) {
            Registry bound = registryRepository.findById(image.getRegistryId()).orElse(null);
            if (bound != null && sameHostOrUnset(bound, imageHost)) {
                return bound;
            }
        }

        // Docker Hub 公共镜像不带注册中心凭据
        if (imageHost == null) {
            return null;
        }

        List<Registry> candidates = registryRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"))
                .stream()
                .filter(r -> imageHost.equalsIgnoreCase(ImageUrlUtils.hostOf(r.getUrl())))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 2) 同一 host 下按仓库前缀取最长匹配（ztcn/ 不会匹配上 ztcn2/）
        Registry best = null;
        int bestLength = -1;
        for (Registry candidate : candidates) {
            String base = ImageUrlUtils.repoBaseOf(candidate);
            if (base != null && isRepoPrefix(base, repoUrl) && base.length() > bestLength) {
                best = candidate;
                bestLength = base.length();
            }
        }
        if (best != null) {
            return best;
        }

        // 3) 镜像命名空间与注册中心配置对不上：默认值优先，其次取最近更新的一个
        String defaultId = systemConfigService.getDefaultRegistryId();
        if (StrUtil.isNotBlank(defaultId)) {
            for (Registry candidate : candidates) {
                if (defaultId.equals(candidate.getId())) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private static boolean sameHostOrUnset(Registry registry, String host) {
        String registryHost = ImageUrlUtils.hostOf(registry.getUrl());
        return registryHost == null || registryHost.equalsIgnoreCase(host);
    }

    /** base 是 repoUrl 的仓库前缀（按 "/" 边界比较）。 */
    private static boolean isRepoPrefix(String base, String repoUrl) {
        return repoUrl.equalsIgnoreCase(base) || StrUtil.startWithIgnoreCase(repoUrl, base + "/");
    }

    /** 默认注册中心；兼容旧版单记录数据，多条记录时要求配置默认值。 */
    public Registry getEffective() {
        String id = systemConfigService.getDefaultRegistryId();
        if (StrUtil.isNotBlank(id)) {
            return registryRepository.findById(id)
                    .orElseThrow(() -> new BusinessException("系统默认注册中心不存在，请到设置-全局设置重新选择"));
        }
        List<Registry> list = registryRepository.findAll(Sort.by(Sort.Direction.DESC, "updateTime"));
        return list.size() == 1 ? list.get(0) : null;
    }

    /**
     * 解析指定注册中心：未指定（为空）时回退系统默认，未配置系统默认时返回 null（不抛异常）。
     * 指定了但该注册中心不存在（如已被删除）时直接抛业务异常，不回退，
     * 避免镜像地址被静默改写到别的注册中心。
     */
    public Registry effectiveFor(String registryId) {
        if (StrUtil.isBlank(registryId)) {
            return getEffective();
        }
        return registryRepository.findById(registryId)
                .orElseThrow(() -> new BusinessException(
                        "指定的注册中心不存在（可能已被删除）：" + registryId + "，请重新选择目标注册中心"));
    }

    public Registry requireRegistry(String registryId) {
        if (StrUtil.isBlank(registryId)) {
            Registry effective = getEffective();
            if (effective == null) throw new BusinessException("未指定注册中心，请到设置-全局设置设置默认注册中心");
            return effective;
        }
        return registryRepository.findById(registryId)
                .orElseThrow(() -> new BusinessException("指定的注册中心不存在：" + registryId));
    }

    /** 无 ID 新增，有 ID 更新；保留旧接口兼容。 */
    @Transactional
    public Registry saveRegistry(Registry input, List<String> updateFields) {
        if (StrUtil.isBlank(input.getId())) {
            Assert.hasText(input.getUrl(), "请填写注册中心地址");
            Assert.hasText(input.getNamespace(), "请填写命名空间");
            return create(input);
        }
        Registry old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "注册中心不存在");
        if (StrUtil.isBlank(input.getPassword())) input.setPassword(old.getPassword());
        return update(input, updateFields);
    }

    @Transactional
    public void deleteRegistry(String id) {
        Assert.hasText(id, "注册中心 ID 不能为空");
        if (id.equals(systemConfigService.getDefaultRegistryId())) {
            throw new BusinessException("该注册中心是系统默认值，请先在设置-全局设置中更换默认注册中心");
        }
        // 被项目绑定为构建目标的注册中心不允许删除：否则项目镜像地址无法解析，列表与构建都会报错
        List<Project> bound = projectRepository.findAll(Spec.<Project>of().eq(Project.Fields.registryId, id));
        if (!bound.isEmpty()) {
            String names = bound.stream().limit(3).map(Project::getName).collect(Collectors.joining("、"));
            String suffix = bound.size() > 3 ? " 等 " + bound.size() + " 个项目" : "";
            throw new BusinessException("该注册中心已被项目 " + names + suffix
                    + " 使用，不能删除，请先修改这些项目的目标注册中心");
        }
        deleteById(id);
    }
}