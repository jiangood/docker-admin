package io.github.jiangood.docker.admin.dto;

import io.github.jiangood.openadmin.util.annotation.Remark;
import lombok.Data;

/**
 * 隧道容器（nps / npc）列表项。
 */
@Remark("隧道容器")
@Data
public class TunnelContainerVo {

    String hostId;

    String hostName;

    String containerId;

    /** nps / npc */
    String role;

    String name;

    String image;

    /** running / exited / ... */
    String state;

    String status;

    /** 是否被平台记录引用 */
    boolean referenced;

    /** 残留：没有被任何平台记录引用的容器 */
    boolean orphan;

}
