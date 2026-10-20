package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像补录/同步结果汇总。
 */
@Data
public class RemoteSyncResult {

    /**
     * 新建的镜像数。
     */
    private int imageAdded;

    /**
     * 已存在跳过的镜像数。
     */
    private int imageSkipped;

    /**
     * 新建的版本数。
     */
    private int tagAdded;

    /**
     * 已存在跳过的版本数。
     */
    private int tagSkipped;

    /**
     * 填充了构建时间等元数据的版本数。
     */
    private int metaFilled;

    /**
     * 逐镜像/逐版本的失败原因，不中断整体同步。
     */
    private List<String> failures = new ArrayList<>();

}
