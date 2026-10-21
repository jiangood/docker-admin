package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器配置元数据：镜像标签表（ImageTag）中声明的端口/卷。
 * <p>
 * 声明只用于新建应用时预填默认值，用户可自由增删改，后端不再要求配置与声明一致。
 */
@Data
public class ImageConfigMetaVo {

    String imageUrl;

    /**
     * 镜像声明的端口，新建应用时作为默认映射（主机端口默认等于容器端口）。
     */
    List<PortMeta> ports = new ArrayList<>();

    /**
     * 镜像声明的卷，新建应用时作为默认挂载（主机路径由前端补默认值）。
     */
    List<VolumeMeta> volumes = new ArrayList<>();

    @Data
    public static class PortMeta {
        Integer privatePort;
        String protocol;
    }

    @Data
    public static class VolumeMeta {
        String privateVolume;
    }
}
