package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dto.ImageSummary;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.ImageVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 镜像视图：数据来自镜像版本表（ImageVersion）。
 */
@Service
@RequiredArgsConstructor
public class ImageService {

    private final AppRepository appRepository;
    private final ImageVersionService imageVersionService;

    public List<ImageSummary> listImages(String searchText) {
        List<ImageVersion> versions = imageVersionService.findAllVersion();
        Map<String, List<ImageVersion>> grouped = versions.stream()
                .filter(v -> StrUtil.isNotBlank(v.getImageUrl()))
                .collect(Collectors.groupingBy(ImageVersion::getImageUrl));

        List<ImageSummary> result = new ArrayList<>();
        for (Map.Entry<String, List<ImageVersion>> entry : grouped.entrySet()) {
            String imageUrl = entry.getKey();
            if (StrUtil.isNotBlank(searchText) && !StrUtil.containsIgnoreCase(imageUrl, searchText)) {
                continue;
            }

            List<String> tags = entry.getValue().stream()
                    .map(ImageVersion::getTag)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .toList();

            ImageSummary summary = new ImageSummary();
            summary.setImageUrl(imageUrl);
            summary.setLatestTag(tags.isEmpty() ? null : tags.get(0));
            summary.setTagCount((long) tags.size());
            summary.setLastBuildTime(entry.getValue().stream()
                    .map(ImageVersion::getCreateTime)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null));
            summary.setAppCount((long) appRepository.findAllByImageUrl(imageUrl).size());
            result.add(summary);
        }

        result.sort(Comparator.comparing(ImageSummary::getLastBuildTime,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return result;
    }

    /**
     * 某个镜像的所有版本（tag），倒序。
     */
    public List<String> tags(String imageUrl) {
        return imageVersionService.tags(imageUrl);
    }

    /**
     * 使用该镜像的应用。
     */
    public List<App> apps(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return List.of();
        }
        return appRepository.findAllByImageUrl(imageUrl);
    }
}
