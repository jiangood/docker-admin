package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像导出任务状态：列表页展示下载状态、导出抽屉判断该显示什么，都用它。
 */
@Data
public class ImageExportTaskView {

    /**
     * 完整镜像地址（不含 tag）。
     */
    private String imageUrl;

    private String tag;

    /**
     * 后台任务标识，同时是实时日志与临时 tar 的文件名。
     */
    private String logId;

    /**
     * RUNNING / SUCCESS / FAILED。
     */
    private String status;

    /**
     * 归档是否已就绪、可以下载。
     */
    private boolean ready;

    /**
     * 归档字节数，ready 时有效。
     */
    private long size;

    private long startTime;

}
