package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 构建测试请求：以 Git 仓库（或指定子目录）为构建上下文，用粘贴的 Dockerfile 覆盖后，在默认构建节点本地构建镜像。
 */
@Data
public class BuildTestRequest {

    /** 可选构建主机 ID；为空时使用系统默认构建主机。 */
    String hostId;

    /** 可选注册中心 ID，用于构建时访问私有基础镜像；为空时使用系统默认注册中心。 */
    String registryId;

    /**
     * Git 仓库地址，凭据按地址主机自动匹配代码源。
     */
    String gitUrl;

    /**
     * 构建上下文：相对代码仓库根目录的目录，留空表示仓库根目录。
     * 粘贴的 Dockerfile 会写入该目录下的 Dockerfile 文件，并以其作为构建上下文。
     */
    String context;

    /**
     * 粘贴的 Dockerfile 内容，覆盖仓库中同路径文件。
     */
    String dockerfileText;

    /**
     * 本地镜像名:标签，默认 temp:latest。
     */
    String targetImage = "temp:latest";
}
