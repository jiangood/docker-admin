package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 镜像下载任务状态：镜像下载页据此展示 tar 的保存位置、大小与下载入口。
 * <p>
 * 任务执行中或失败时 ready=false；成功且文件仍存在时 ready=true。
 */
@Data
public class ImageDownloadTaskView {

    /** 后台任务标识，同时用于定位数据库记录与 tar 目录。 */
    private String logId;

    private String image;

    private String hostName;

    /** 下载时使用的文件名，如 mysql-8.tar。 */
    private String fileName;

    /** tar 在平台服务器上的保存位置（{@code {数据目录}/image-tar/{logId}/}）。 */
    private String path;

    /** 文件大小（字节）。 */
    private long size;

    /** RUNNING / SUCCESS / FAILED / CLEANING / CLEANED。 */
    private String status;

    private String error;

    /** 是否已生成、文件仍存在且可以下载。 */
    private boolean ready;

}
