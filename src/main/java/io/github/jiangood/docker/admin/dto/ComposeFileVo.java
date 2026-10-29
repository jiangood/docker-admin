package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * compose 项目目录下的一个可编辑文件（compose 文件或 .env）。
 */
@Data
public class ComposeFileVo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文件名（不含目录） */
    String name;

    /** 宿主机上的绝对路径 */
    String path;

    /** 文件大小（字节） */
    long size;

    /** 最后修改时间（epoch millis） */
    long mtime;

    /** 八进制权限，如 644 */
    String mode;
}
