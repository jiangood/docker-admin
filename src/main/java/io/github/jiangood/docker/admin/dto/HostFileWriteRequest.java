package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 保存主机文本文件的请求。
 * <p>
 * path 是相对于辅助容器挂载目录的相对路径，例如 {@code conf/app.yml}；
 * 根目录用空字符串表示。
 */
@Data
public class HostFileWriteRequest {

    String hostId;

    String path;

    /** 覆盖写入的文本内容（UTF-8）。 */
    String content;
}
