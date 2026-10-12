package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远程仓库预览结果：仓库下已存在的每个版本。
 */
@Data
public class RemoteTagVo {

    /**
     * 镜像仓库地址（不含 tag），与 {@code t_image_repo.imageUrl} 同格式。
     */
    private String imageUrl;

    private String tag;

    /**
     * 本地是否已登记该版本。
     */
    private boolean exists;

    /**
     * 镜像构建时间，来自镜像配置的 created 字段；仅同步阶段填充。
     */
    private LocalDateTime buildTime;

    /**
     * 镜像压缩后总大小（字节）；仅同步阶段填充。
     */
    private Long size;

    /**
     * 镜像配置摘要；仅同步阶段填充。
     */
    private String digest;

    /**
     * 读取元数据失败时的原因；不影响其余版本。
     */
    private String metaError;

    /**
     * 按仓库分组后的视图。
     */
    @Data
    public static class RepoGroup {
        private String imageUrl;
        private String error;
        private List<RemoteTagVo> tags;
    }

}