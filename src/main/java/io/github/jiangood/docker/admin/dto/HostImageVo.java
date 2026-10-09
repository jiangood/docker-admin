package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主机上的镜像。
 */
@Data
public class HostImageVo implements Serializable {
    private static final long serialVersionUID = 1L;

    String id;
    String idShort;
    List<String> repoTags = new ArrayList<>();
    List<String> repoDigests = new ArrayList<>();
    Long size;
    Long created;
    Integer containers;
    Map<String, String> labels = new LinkedHashMap<>();
}
