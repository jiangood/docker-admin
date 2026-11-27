package io.github.jiangood.docker.admin.dto;

import io.github.jiangood.docker.admin.entity.App;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 「容器转为应用」的预填数据：按容器 inspect 结果生成一个尚未保存的应用，
 * 附带容器信息与转换时需要注意的点，供前端弹窗展示 / 修改后再提交。
 */
@Data
public class ConvertToAppVo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 预填的应用（id 为空，host 已指向容器所在主机） */
    App app;

    String containerId;
    String containerName;
    String containerState;

    /**
     * 容器已经由某个平台应用管理时，该应用名称；非空时不允许重复转换。
     */
    String managedAppName;

    /**
     * 无法完整带过去、或转换后行为会变化的配置说明。
     */
    List<String> warnings = new ArrayList<>();
}
