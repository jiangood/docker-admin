package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像同步请求，按 {@link #syncType} 分三种：
 * <ol>
 *   <li>{@code registry}：经执行主机拉取后推送到平台注册中心，并登记镜像与标签；</li>
 *   <li>{@code host}：各目标主机直接拉取源镜像，不经过注册中心；</li>
 *   <li>{@code relay}：执行主机拉取后中转给各目标主机（save/load），不经过注册中心。</li>
 * </ol>
 */
@Data
public class ImageSyncRequest {

    /**
     * 同步类型：{@code registry} / {@code host} / {@code relay}，详见类注释。
     * 为空按 {@code registry} 处理。
     */
    String syncType;

    /**
     * 执行主机 id：registry 为负责拉取并推送的主机，relay 为负责拉取并中转的主机；
     * host（直连）模式不需要。
     */
    String hostId;

    /** 目标注册中心 ID；留空时使用系统默认注册中心。仅 registry 模式使用。 */
    String targetRegistryId;

    /**
     * 源镜像，如 nginx:1.25-alpine、docker.io/library/redis:7。
     */
    String sourceImage;

    /**
     * 推送后在注册中心上的镜像名，如 my-nginx；留空按源镜像自动推导。
     * 仅 registry 模式使用，只允许小写字母、数字与 _ . -。
     */
    String targetName;

    /**
     * 推送后在注册中心上的版本号，如 1.25.3；留空沿用源镜像的 tag。
     * 仅 registry 模式使用。
     */
    String targetTag;

    /**
     * 目标主机 id：host / relay 模式下镜像最终要落到的主机；registry 模式不使用。
     */
    String targetHostId;

}
