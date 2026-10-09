package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器详情：基本信息 + 只读配置（来自 docker inspect）。
 */
@Data
public class ContainerDetailVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String id;
    String idShort;
    String name;
    String image;
    String imageId;

    String state;       // running / exited / created / paused ...
    String status;      // 人类可读状态
    Boolean running;
    Boolean paused;
    Boolean restarting;
    Boolean dead;
    Integer exitCode;
    String error;
    Integer restartCount;

    String created;
    String startedAt;
    String finishedAt;

    // ---- 配置 ----
    String[] cmd;
    String[] entrypoint;
    String workingDir;
    String user;
    String hostname;
    Boolean privileged;
    String networkMode;
    String restartPolicy;
    String logDriver;
    List<String> env = new ArrayList<>();
    Map<String, String> labels = new LinkedHashMap<>();

    List<PortVo> ports = new ArrayList<>();
    List<MountVo> mounts = new ArrayList<>();
    List<NetworkVo> networks = new ArrayList<>();

    @Data
    public static class PortVo implements Serializable {
        private static final long serialVersionUID = 1L;
        Integer privatePort;
        Integer publicPort;
        String protocol;
        String hostIp;
    }

    @Data
    public static class MountVo implements Serializable {
        private static final long serialVersionUID = 1L;
        String type;        // bind / volume
        String name;
        String source;
        String destination;
        String mode;
        Boolean readOnly;
    }

    @Data
    public static class NetworkVo implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        String ipAddress;
        String gateway;
        String macAddress;
    }
}
