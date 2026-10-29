package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 一次 compose 命令（pull / up / down）的执行状态。
 * <p>
 * 与镜像导出一样只存内存：日志正文在 {@code ${logging.file.path}/{logId}.log}，
 * 进程重启后进行中的任务丢失，但已产生的日志文件仍在。
 */
@Data
public class ComposeTaskVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String logId;
    String hostId;
    String dir;
    /** pull / up / down */
    String action;
    /** RUNNING / SUCCESS / FAILED / CANCELLED */
    String status;
    /** 容器退出码，取消或异常时为空 */
    Integer exitCode;
    long startTime;
    Long endTime;
    /** 一句话结果说明（失败原因、取消等） */
    String message;
}
