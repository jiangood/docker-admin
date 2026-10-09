package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 容器内单个文件/目录条目。
 */
@Data
public class ContainerFileVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String name;
    String path;
    String type;   // dir / file / link / other
    Long size;
    String mode;
    String mtime;
    String linkTarget;
}
