package io.github.jiangood.docker.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 远程注册中心查询参数。
 * <p>
 * 凭据只在本次请求里使用，不落库。
 */
@Data
public class RemoteRegistryQuery {

    /**
     * 注册中心地址，如 {@code registry.cn-hangzhou.aliyuncs.com}、{@code https://harbor.example.com}。
     * 省略协议时按 https 处理。
     */
    private String url;

    /**
     * 命名空间。阿里云个人版即账号 UID；留空表示不过滤。
     */
    private String namespace;

    private String username;

    private String password;

    /**
     * 要处理的镜像仓库地址列表，可同时写相对路径（{@code ztcn/nginx}）
     * 和完整地址（{@code registry.cn-hangzhou.aliyuncs.com/ztcn/nginx}）。
     * <p>
     * 为空时由 {@link #catalogOnly} 的发现结果填充。
     */
    private List<String> imageUrls;

    /**
     * 是否读取镜像元数据（构建时间、大小、摘要）。每个版本需额外 2 次请求，预览阶段固定不读。
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