package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 手工补录镜像的参数。
 * <p>
 * 镜像地址与版本都由用户填写，不访问注册中心。
 */
@Data
public class ManualImageRequest {

    /**
     * 镜像地址：{@code registry.url/namespace/name}，允许直接带 {@code :tag}。
     */
    private String imageUrl;

    /**
     * 版本号列表。
     */
    private List<String> tags;

    /**
     * 备注，可空。
     */
    private String remark;

}
