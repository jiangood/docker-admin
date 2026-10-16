package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 保存容器内文本文件的请求。
 * <p>
 * 以 hostId + containerId 定位容器，path 为容器内文件的绝对路径，content 为覆盖写入的文本内容。
 */
@Data
public class ContainerFileWriteRequest {

    String hostId;

    String containerId;

    /** 容器内文件的绝对路径。 */
    String path;

    /** 覆盖写入的文本内容（UTF-8）。 */
    String content;
}
