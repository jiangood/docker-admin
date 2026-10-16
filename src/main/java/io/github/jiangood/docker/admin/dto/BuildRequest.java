package io.github.jiangood.docker.admin.dto;

import lombok.Data;

@Data
public class BuildRequest {

    /** 按版本 tag 构建（默认）。 */
    public static final String REF_TYPE_TAG = "TAG";

    /** 按分支最新代码构建。 */
    public static final String REF_TYPE_BRANCH = "BRANCH";

    String projectId;

    /**
     * 构建来源类型：{@link #REF_TYPE_TAG}（默认）或 {@link #REF_TYPE_BRANCH}。
     */
    String refType = REF_TYPE_TAG;

    /**
     * 版本 tag，同时作为镜像版本号（如 v1.0.1）；仅 {@code refType=TAG} 时使用。
     */
    String tag;

    /**
     * 分支名；仅 {@code refType=BRANCH} 时使用，克隆该分支最新代码，
     * 镜像版本号由「分支名 + 提交短 id」生成（见 ProjectService#buildBranchImageTag）。
     */
    String branch;

    String context = "/";
    String dockerfile = "Dockerfile";
    // 是否复用构建缓存（手动触发不暴露开关，默认复用）
    boolean useCache = true;

    /**
     * 构建节点 id，为空时使用系统默认 runner。
     */
    String buildHostId;

    /** 本次构建目标注册中心 ID；为空时使用系统默认注册中心。 */
    String registryId;

    // 构建时是否拉取基础镜像（手动触发不暴露开关，默认不拉取）
    boolean pull = false;

}
