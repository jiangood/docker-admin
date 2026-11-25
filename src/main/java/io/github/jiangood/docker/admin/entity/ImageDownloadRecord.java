package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 镜像下载记录：任务开始即落库，tar 生成后保留到用户在页面勾选批量清理。
 * <p>
 * 记录与文件都不会因为其他用户开始新任务而被删除；平台重启后仍可查看历史，
 * 运行中的遗留记录由启动清扫标记为失败。
 */
@Remark("镜像下载记录")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_image_download_record", indexes = {
        @Index(name = "uk_image_download_log_id", columnList = "log_id", unique = true),
        @Index(name = "idx_image_download_status", columnList = "status"),
        @Index(name = "idx_image_download_create_time", columnList = "create_time")
})
public class ImageDownloadRecord extends BaseEntity {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CLEANING = "CLEANING";
    public static final String CLEANED = "CLEANED";

    /** 后台任务标识，同时是日志文件名与 tar 目录名。 */
    @Column(length = 64, nullable = false)
    String logId;

    /** 用户提交的镜像地址快照。 */
    @Column(length = 512, nullable = false)
    String image;

    /** 执行主机快照，主机删除后历史记录仍可读。 */
    @Column(length = 64)
    String hostId;

    @Column(length = 255)
    String hostName;

    /** 触发任务的登录用户名。 */
    @Column(length = 64)
    String triggerUser;

    /** RUNNING / SUCCESS / FAILED / CLEANING / CLEANED。 */
    @Column(length = 32, nullable = false)
    String status;

    @Column(length = 255)
    String fileName;

    /** tar 在平台服务器上的绝对路径。 */
    @Column(length = 1000)
    String path;

    /** 文件大小（字节）。 */
    Long size;

    /** 失败原因；只保留面向用户的简短信息。 */
    @Column(length = 1000)
    String error;

    LocalDateTime endTime;

    LocalDateTime cleanedTime;

    /** 执行清理的登录用户名；启动时补齐缺失文件的历史记录记为 SYSTEM。 */
    @Column(length = 64)
    String cleanedBy;

    /** 列表展示用，不落库：成功且文件仍存在。 */
    @Transient
    Boolean ready;

}
