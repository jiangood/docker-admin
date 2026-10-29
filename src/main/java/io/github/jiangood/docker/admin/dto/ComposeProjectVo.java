package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 主机上的一个 compose 项目（一个项目目录 + 其中的 compose 文件）。
 */
@Data
public class ComposeProjectVo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** compose 项目名：来自 {@code docker compose ls}，手工打开的目录取目录名 */
    String name;

    /** 项目目录（宿主机上的绝对路径） */
    String dir;

    /** 目录里的主 compose 文件（相对项目目录的文件名） */
    String composeFile;

    /** running / exited / partial / unknown */
    String status;

    /** compose ls 给出的原始状态描述，如 running(2) */
    String statusText;

    /** 运行中的服务数 */
    Integer running;

    /** 服务总数；未启动的项目无法得知，为空 */
    Integer total;

    /** 项目目录下可编辑的文件 */
    List<ComposeFileVo> files = new ArrayList<>();
}
