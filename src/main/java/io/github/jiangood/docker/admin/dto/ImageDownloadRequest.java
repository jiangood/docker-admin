package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像下载 tar 请求：在指定主机上拉取镜像并打成 {@code docker save} 的 tar 包。
 */
@Data
public class ImageDownloadRequest {

    /**
     * 镜像地址，含版本，如 mysql:8、nginx:1.27-alpine。
     */
    String image;

    /**
     * 执行主机 id：拉取镜像并生成 tar 包的主机。
     */
    String hostId;

}
