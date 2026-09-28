package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器配置元数据：镜像表（ImageVersion）中声明的端口/卷，并合并已保存的主机侧映射值。
 * <p>
 * 只有当镜像来自镜像表且声明非空时，对应维度才为严格模式（容器侧只读、不可增删）。
 */
@Data
public class ImageConfigMetaVo {

    String imageUrl;

    String imageTag;

    /**
     * 端口是否严格：镜像来自镜像表且声明了端口。严格时容器端口/协议只读、不可增删。
     */
    boolean strictPorts;

    /**
     * 卷是否严格：镜像来自镜像表且声明了卷。严格时容器路径只读、不可增删。
     */
    boolean strictVolumes;

    /**
     * 端口列表。
     */
    List<PortMeta> ports = new ArrayList<>();

    /**
     * 卷列表。
     */
    List<VolumeMeta> volumes = new ArrayList<>();

    @Data
    public static class PortMeta {
        Integer privatePort;
        String protocol;
        Integer publicPort;
    }

    @Data
    public static class VolumeMeta {
        String privateVolume;
        String publicVolume;
        Boolean readOnly;
    }
}
