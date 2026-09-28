package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.dao.BuildLogRepository;
import io.github.jiangood.docker.admin.dto.ImageSummary;
import io.github.jiangood.docker.admin.entity.App;
import io.github.jiangood.docker.admin.entity.BuildLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 镜像视图：数据来自构建记录（BuildLog），不依赖注册中心 API。
 */
@Service
@RequiredArgsConstructor
public class ImageService {

    private final BuildLogRepository buildLogRepository;
    private final AppRepository appRepository;

    public List<ImageSummary> listImages(String searchText) {
        List<BuildLog> logs = buildLogRepository.findAll();
        Map<String, List<BuildLog>> grouped = logs.stream()
                .filter(l -> StrUtil.isNotBlank(l.getImageUrl()))
                .collect(Collectors.groupingBy(BuildLog::getImageUrl));

        List<ImageSummary> result = new ArrayList<>();
        for (Map.Entry<String, List<BuildLog>> entry : grouped.entrySet()) {
            String imageUrl = entry.getKey();
            if (StrUtil.isNotBlank(searchText) && !StrUtil.containsIgnoreCase(imageUrl, searchText)) {
                continue;
            }
            List<BuildLog> success = entry.getValue().stream()
                    .filter(l -> Boolean.TRUE.equals(l.getSuccess()))
                    .toList();

            List<String> tags = success.stream()
                    .map(BuildLog::getVersion)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .toList();

            ImageSummary summary = new ImageSummary();
            summary.setImageUrl(imageUrl);
            summary.setLatestTag(tags.isEmpty() ? null : tags.get(0));
            summary.setTagCount((long) tags.size());
            summary.setLastBuildTime(entry.getValue().stream()
                    .map(BuildLog::getCreateTime)
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
     * 某个镜像的成功构建版本（tag），倒序。
     */
    public List<String> tags(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return List.of();
        }
        return buildLogRepository.findAll().stream()
                .filter(l -> imageUrl.equals(l.getImageUrl()))
                .filter(l -> Boolean.TRUE.equals(l.getSuccess()))
                .map(BuildLog::getVersion)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
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
