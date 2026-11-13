package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 通用后台任务记录：长任务（Agent 部署、镜像同步、构建、部署、导出等）的统一台账。
 * <p>
 * 一条记录对应一个 {@code logId}，日志正文不落库，仍在 {@code /data/logs/{logId}.log}，
 * WebSocket {@code /admin/ws/task-log/{logId}} 据此实时推送。
 * <p>
 * 与各业务自有记录（如 {@link ImageSyncLog}、DeployLog、BuildLog）并存：业务表保留领域字段，
 * 本表只做「任务在不在跑、成没成功、去哪看日志」的统一视图，供「任务中心」页展示。
 *
 * @see io.github.jiangood.docker.admin.service.LongTaskRunner
 */
@Remark("后台任务记录")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_task_log", indexes = {
        @Index(name = "idx_task_log_logId", columnList = "logId"),
        @Index(name = "idx_task_log_status", columnList = "status"),
})
public class TaskLog extends BaseEntity {

    /** 运行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 成功结束。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 失败（含取消、平台重启中断）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 任务类型：项目构建（logId 即构建记录 id）。 */
    public static final String TYPE_BUILD = "build";
    /** 任务类型：应用部署（logId 即部署记录 id）。 */
    public static final String TYPE_DEPLOY = "deploy";
    /** 任务类型：构建测试。 */
    public static final String TYPE_BUILD_TEST = "build-test";
    /** 任务类型：镜像同步。 */
    public static final String TYPE_IMAGE_SYNC = "image-sync";
    /** 任务类型：镜像导出。 */
    public static final String TYPE_IMAGE_EXPORT = "image-export";
    /** 任务类型：镜像导入。 */
    public static final String TYPE_IMAGE_IMPORT = "image-import";
    /** 任务类型：镜像下载。 */
    public static final String TYPE_IMAGE_DOWNLOAD = "image-download";
    /** 任务类型：主机镜像导出。 */
    public static final String TYPE_HOST_IMAGE_EXPORT = "host-image-export";
    /** 任务类型：Agent 部署 / 更新 / 切换挂载目录。 */
    public static final String TYPE_AGENT_DEPLOY = "agent-deploy";

    /**
     * 任务类型，如 agent-deploy / deploy / build / build-test / image-sync / image-export。
     * 同时也是该类型新任务 logId 的前缀。
     */
    String type;

    /**
     * 日志标识，即日志文件名（不含 .log），唯一。
     */
    @Column(unique = true, length = 64)
    String logId;

    /** 人类可读描述，如「nginx-1 · 部署辅助容器」。 */
    @Column(length = 200)
    String title;

    /** 状态：{@link #STATUS_RUNNING} / {@link #STATUS_SUCCESS} / {@link #STATUS_FAILED}。 */
    @Column(length = 20)
    String status;

    /**
     * 查看该任务实时日志所需的权限码（如 host:list），WebSocket 握手拦截器据此鉴权。
     * 为空表示不允许通过通用端点查看（仍可走各业务自己的端点）。
     */
    @Column(length = 64)
    String permission;

    /** 触发人；定时任务或系统内部触发为 SYSTEM。 */
    @Column(length = 64)
    String triggerUser;

    /** 失败原因，成功时为空。 */
    @Column(length = 1000)
    String error;

    /** 进度快照 JSON（如已写入大小、速度、百分比），可选，运行中由任务更新。 */
    @Column(length = 1000)
    String progress;

    LocalDateTime endTime;

    /** 耗时（毫秒）：createTime 到 endTime。 */
    Long timeSpend;

    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

}
