package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 主机辅助容器（文件管理 / Docker CLI）的状态。
 */
@Data
public class HostAgentVo {

    /** 是否已部署（同名但不是本平台创建的容器也算未部署，见 message）。 */
    boolean deployed;

    /** 容器是否正在运行。 */
    boolean running;

    /** 配置的目录/镜像与容器实际参数不一致，需要重建。 */
    boolean needsRebuild;

    String containerId;

    String containerName;

    /** 当前全局配置的镜像地址（不含 tag）。 */
    String repository;

    /** 该主机选择的镜像 tag。 */
    String tag;

    /** 期望使用的完整镜像：repository + ":" + tag。 */
    String image;

    /** 容器实际使用的完整镜像（来自容器 label）。 */
    String mountedImage;

    /** 当前配置的 docker socket 宿主机路径。 */
    String socketPath;

    /** 期望挂载的宿主机目录（Agent 配置或默认 /home）。 */
    String desiredRoot;

    /** 容器实际挂载的宿主机目录（来自容器 label）。 */
    String mountedRoot;

    /** 面向用户的提示，例如同名容器冲突。 */
    String message;
}
