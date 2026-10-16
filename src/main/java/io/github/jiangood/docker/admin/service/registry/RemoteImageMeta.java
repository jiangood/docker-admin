package io.github.jiangood.docker.admin.service.registry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远程镜像的元数据快照。
 *
 * @param buildTime    镜像构建时间（image config 的 created 字段），可能为空
 * @param digest       镜像配置摘要（sha256:...），可能为空
 * @param size         镜像压缩后总大小（各层 + 配置），未知时为空
 * @param exposedPorts 镜像声明的端口（image config 的 config.ExposedPorts），形如 80/tcp，可能为空
 * @param volumes      镜像声明的卷路径（image config 的 config.Volumes），如 /data，可能为空
 */
public record RemoteImageMeta(LocalDateTime buildTime, String digest, Long size,
                              List<String> exposedPorts, List<String> volumes) {
}