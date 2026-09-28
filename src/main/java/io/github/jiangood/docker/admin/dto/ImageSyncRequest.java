package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像同步请求：把公共镜像经指定主机拉取后推送到平台注册中心。
 */
@Data
public class ImageSyncRequest {

    /**
     * 同步主机 id（网络通畅、能访问公共镜像仓库的节点）。
     */
    String hostId;

    /**
     * 源镜像，如 nginx:1.25-alpine、docker.io/library/redis:7。
     */
    String sourceImage;

    /**
     * 目标镜像名，留空则取源镜像的最后一段。
     */
    String targetName;

    /**
     * 平台，如 linux/amd64、linux/arm64，留空按宿主机架构。
     */
    String platform;

}
