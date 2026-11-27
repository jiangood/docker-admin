package io.github.jiangood.docker.admin.dto;

import io.github.jiangood.docker.admin.entity.App;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 「容器转为应用」提交参数。
 */
@Data
public class ConvertToAppRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull
    String hostId;

    @NotNull
    String containerId;

    /** 用户在弹窗里确认的应用信息（名称 / 镜像 / 组织 / 备注 / 容器配置） */
    App app;

    /**
     * 是否在登记后立即按应用配置重建容器（会短暂中断服务）。
     * 为 false 时保留当前容器，平台按 id / 名称接管。
     */
    Boolean recreate;
}
