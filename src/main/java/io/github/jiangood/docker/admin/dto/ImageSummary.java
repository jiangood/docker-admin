package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 镜像概览（按构建记录聚合）。
 */
@Data
public class ImageSummary {

    String imageUrl;

    String latestTag;

    Long tagCount;

    LocalDateTime lastBuildTime;

    Long appCount;

}
