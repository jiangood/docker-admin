package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 主机 Docker 引擎信息（docker info + version）。
 */
@Data
public class HostInfoVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String name;
    String connectionType;
    String endpoint;

    String dockerVersion;
    String apiVersion;
    String minApiVersion;
    String os;
    String arch;
    String kernelVersion;
    String serverName;
    String loggingDriver;
    String cgroupDriver;

    Long memory;
    Integer cpus;

    Integer containersRunning;
    Integer containersStopped;
    Integer containersTotal;
    Integer images;
}
