package io.github.jiangood.docker.admin.dto;

import lombok.Data;

@Data
public class BuildRequest {

    String projectId;

    /**
     * 构建用的 git tag，同时作为镜像版本号（如 v1.0.1）。
     */
    String tag;

    String context = "/";
    String dockerfile = "Dockerfile";
    boolean useCache = true;

    String buildHostId = "default";

    // 构建时是否拉取最近镜像
    boolean pull = false;

}
