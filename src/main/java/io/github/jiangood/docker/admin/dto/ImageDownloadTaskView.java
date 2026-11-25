package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像下载任务状态：镜像下载页据此展示 tar 的保存位置、大小与下载入口。
 * <p>
 * 只有任务成功生成 tar 后才有值；任务执行中或失败时接口返回 null，进度与原因看实时日志。
 */
@Data
public class ImageDownloadTaskView {

    /**
     * 后台任务标识，同时用于定位内存中的结果。
     */
    private String logId;

    /**
     * 下载时使用的文件名，如 mysql-8.tar。
     */
    private String fileName;

    /**
     * tar 在平台服务器上的保存位置（{@code {数据目录}/image-tar/{logId}/}）。
     */
    private String path;

    /**
     * 文件大小（字节）。
     */
    private long size;

    /**
     * 是否已生成、可以下载。
     */
    private boolean ready;

}
