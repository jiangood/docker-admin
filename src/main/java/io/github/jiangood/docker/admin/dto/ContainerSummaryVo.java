package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器列表条目（用于主机详情的容器表格）。
 */
@Data
public class ContainerSummaryVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String id;
    String idShort;
    String name;
    String image;
    String imageId;
    String state;
    String status;
    Long created;
    String command;
    Map<String, String> labels = new LinkedHashMap<>();
    List<ContainerDetailVo.PortVo> ports = new ArrayList<>();
}
