package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 从系统已配置的镜像注册中心补录时的查询参数。
 * <p>
 * 注册中心地址、命名空间、账号密码一律取「系统设置 → 镜像注册中心」里已保存的那一条，
 * 不接受调用方传入——既避免每次都重复输入凭据，也顺带消除了凭据落库的可能。
 */
@Data
public class RemoteRegistryQuery {

    /**
     * 要导入的镜像仓库地址列表，可写相对路径（{@code ztcn/nginx}）或完整地址
     * （{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx}）。
     * <p>
     * 为空时按注册中心列举出的全部仓库处理。
     */
    private List<String> imageUrls;

    /**
     * 是否读取镜像元数据（构建时间、大小、摘要）。每个版本需额外 2 次请求，版本多的仓库会明显变慢。
     */
    private boolean withMeta = true;

    /**
     * 自动发现仓库时最多取多少个，0 表示不限。
     */
    private int maxRepos = 500;

    /**
     * 每个仓库最多处理的版本数，0 表示不限。用于防止一次同步拉爆注册中心。
     */
    private int maxTags = 100;

}