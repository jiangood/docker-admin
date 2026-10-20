package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.ManualImageRequest;
import io.github.jiangood.docker.admin.dto.RemoteSyncResult;
import io.github.jiangood.docker.admin.entity.Image;
import io.github.jiangood.docker.admin.util.ImageUrlUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手工补录镜像：镜像地址与版本号都由用户填写，不访问注册中心。
 * <p>
 * 用于登记平台外已经构建/推送、但本平台还没有记录的镜像，让应用可以直接引用。
 * 已存在的镜像与版本跳过，不覆盖已有的来源标记与元数据。
 */
@Service
@RequiredArgsConstructor
public class ImageManualService {

    /**
     * 版本号遵循 docker tag 的允许字符，避免登记出无法拉取的版本。
     */
    private static final String TAG_PATTERN = "[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}";

    private final ImageService imageService;

    private final ImageTagService imageTagService;

    /**
     * 登记一个镜像及其版本。镜像或版本已存在时只计数跳过，不报错。
     */
    @Transactional
    public RemoteSyncResult save(ManualImageRequest request) {
        Assert.notNull(request, "请求不能为空");
        String raw = StrUtil.trimToEmpty(request.getImageUrl());
        String imageUrl = normalizeUrl(raw);
        Assert.hasText(imageUrl, "请填写镜像地址");

        List<String> tags = normalizeTags(request.getTags(), ImageUrlUtils.tagOf(raw));
        Assert.notEmpty(tags, "请至少填写一个版本号");

        RemoteSyncResult result = new RemoteSyncResult();
        Image image = imageService.findByImageUrl(imageUrl);
        if (image == null) {
            image = imageService.upsert(imageUrl, nameOf(imageUrl), "MANUAL", null, null);
            result.setImageAdded(1);
        } else {
            result.setImageSkipped(1);
        }

        // 只有用户填了备注才写，避免把已有备注清空
        if (image != null && StrUtil.isNotBlank(request.getRemark())) {
            image.setRemark(request.getRemark().trim());
            imageService.save(image);
        }

        for (String tag : tags) {
            if (imageTagService.find(imageUrl, tag).isPresent()) {
                result.setTagSkipped(result.getTagSkipped() + 1);
            } else {
                imageTagService.upsert(imageUrl, tag, "MANUAL");
                result.setTagAdded(result.getTagAdded() + 1);
            }
        }
        return result;
    }

    /**
     * 镜像地址归一化：去掉协议与末尾斜杠；地址里带 {@code :tag} 时把版本拆出去。
     * <p>
     * {@code https://host/ns/name:1.0} 与 {@code host/ns/name} 都会得到 {@code host/ns/name}。
     */
    static String normalizeUrl(String rawUrl) {
        String v = ImageUrlUtils.withoutScheme(StrUtil.trimToEmpty(rawUrl)).trim();
        v = ImageUrlUtils.repoOf(v).trim();
        return StrUtil.removeSuffix(v, "/");
    }

    /**
     * 版本号来源：地址里自带的 {@code :tag} + 用户单独填写的列表，去重并校验。
     * <p>
     * 兼容把多个版本写进同一个输入框（逗号或空格分隔）的调用方式。
     */
    static List<String> normalizeTags(List<String> tags, String tagFromUrl) {
        Set<String> set = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(tagFromUrl)) {
            set.add(tagFromUrl.trim());
        }
        if (tags != null) {
            for (String tag : tags) {
                if (StrUtil.isBlank(tag)) {
                    continue;
                }
                for (String part : tag.split("[,，\\s]+")) {
                    if (StrUtil.isNotBlank(part)) {
                        set.add(part.trim());
                    }
                }
            }
        }
        for (String tag : set) {
            Assert.isTrue(tag.matches(TAG_PATTERN), "版本号不合法：" + tag);
        }
        return new ArrayList<>(set);
    }

    /**
     * 镜像名取地址最后一段：{@code .../ztcn/nginx} -> {@code nginx}。
     */
    static String nameOf(String imageUrl) {
        int idx = imageUrl.lastIndexOf('/');
        return idx < 0 ? imageUrl : imageUrl.substring(idx + 1);
    }

}
