package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 镜像同步请求：把公共镜像经指定主机拉取后推送到平台注册中心；
 * 若选了目标主机，这些主机再从注册中心拉取。
 * <p>
 * 注册中心是必经环节；不选目标主机时只推送到注册中心。
 */
@Data
public class ImageSyncRequest {

    /**
     * 同步主机 id（网络通畅、能访问公共镜像仓库的主机）。
     */
    String hostId;

    /**
     * 源镜像，如 nginx:1.25-alpine、docker.io/library/redis:7。
     */
    String sourceImage;

    /**
     * 目标主机 id 列表，镜像会先推到注册中心，再由这些主机拉取。可为空。
     */
    List<String> targetHostIds;

    /**
     * 目标镜像名，留空则取源镜像的最后一段。
     */
    String targetName;

}
