package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 主机镜像导出任务状态：主机详情页的导出弹窗据此展示进度、日志、保存路径与下载入口。
 */
@Data
public class HostImageExportTaskView {

    /**
     * 后台任务标识，同时是实时日志与临时文件的标识。
     */
    private String logId;

    /**
     * 被导出的镜像标识（镜像 ID 或 repo:tag）。
     */
    private String image;

    /**
     * 执行主机名称。
     */
    private String hostName;

    /**
     * 下载时使用的文件名，如 nginx-1.27.tar。
     */
    private String fileName;

    /**
     * tar 在平台服务器上的保存位置。
     */
    private String path;

    /**
     * RUNNING / SUCCESS / FAILED。
     */
    private String status;

    /**
     * 归档是否已生成、可以下载。
     */
    private boolean ready;

    /**
     * 已写入的字节数；任务结束后为最终文件大小。
     */
    private long size;

    /**
     * 写入速度（字节/秒）：运行中为平滑后的当前速度，成功后为整次导出的平均速度。
     */
    private long speed;

    private long startTime;

}
