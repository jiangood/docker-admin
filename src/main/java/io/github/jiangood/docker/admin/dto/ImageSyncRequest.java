package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 镜像同步请求：把公共镜像经指定主机拉取后推送到平台注册中心，和/或传输到其他目标主机。
 * <p>
 * 「目标注册中心」与「目标主机」至少选择一项，可同时选择。
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
     * 是否推送到平台注册中心。
     */
    Boolean toRegistry;

    /**
     * 目标主机 id 列表，镜像会 save 后 load 到这些主机。
     */
    List<String> targetHostIds;

    /**
     * 目标镜像名，留空则取源镜像的最后一段。
     */
    String targetName;

    /**
     * 平台，如 linux/amd64、linux/arm64，留空按宿主机架构。
     */
    String platform;

}
