package io.github.jiangood.docker.admin.dto;

import lombok.Data;

@Data
public class BuildRequest {

    String imageId;

    /**
     * 构建用的 git tag，同时作为镜像版本号（如 v1.0.1）。
     */
    String tag;

    String context = "/";
    String dockerfile = "Dockerfile";
    // 是否复用构建缓存（手动触发不暴露开关，默认复用）
    boolean useCache = true;

    /**
     * 构建节点 id，为空时使用系统默认 runner。
     */
    String buildHostId;

    // 构建时是否拉取基础镜像（手动触发不暴露开关，默认不拉取）
    boolean pull = false;

}
