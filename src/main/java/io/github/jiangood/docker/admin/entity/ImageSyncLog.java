package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 镜像同步记录：每次同步一条，供页面下方「同步记录」表格回看。
 * <p>
 * 只存快照（主机名、注册中心地址），主机或注册中心被删除后记录仍然可读；
 * 日志正文不落库，仍在 {@code /data/logs/{logId}.log}，用 {@link #logId} 定位。
 */
@Remark("镜像同步记录")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_image_sync_log")
public class ImageSyncLog extends BaseEntity {

    /**
     * 同步类型：推到注册中心后结束。
     */
    public static final String TYPE_REGISTRY = "registry";
    /**
     * 同步类型：各目标主机直接拉取源镜像，不经过注册中心。
     */
    public static final String TYPE_HOST = "host";
    /**
     * 同步类型：执行主机拉取源镜像后中转（save/load）给各目标主机，不经过注册中心。
     */
    public static final String TYPE_RELAY = "relay";

    /**
     * 同步日志标识，对应日志文件 {@code {logId}.log}；任务结束后仍可用它回看日志。
     */
    String logId;

    /**
     * 同步类型，见 {@link #TYPE_REGISTRY} / {@link #TYPE_HOST} / {@link #TYPE_RELAY}。
     */
    String syncType;

    /**
     * 源镜像，如 nginx:1.25-alpine。
     */
    String sourceImage;

    /**
     * 目标镜像在注册中心上的完整地址，由源镜像自动生成。
     */
    String targetImage;

    /**
     * 目标注册中心的地址快照（url + 命名空间）。
     */
    String registryUrl;

    String hostId;
    String hostName;

    /**
     * 目标主机：只同步到注册中心时为空。
     */
    String targetHostId;
    String targetHostName;

    /**
     * 成功与否：为空表示仍在进行中。
     */
    Boolean success;

    /**
     * 失败原因，成功时为空。
     */
    @Column(length = 1000)
    String errorMessage;

    LocalDateTime completeTime;

    /**
     * 耗时（毫秒）：开始（createTime）到完成。
     */
    Long timeSpend;

}
