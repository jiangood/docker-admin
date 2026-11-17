package io.github.jiangood.docker.admin.dto;

import lombok.Data;

/**
 * 「检查更新」结果：注册中心里 agent 镜像 tag 当前指向的 manifest 摘要，
 * 与主机上实际运行镜像比较后的结论。只读 manifest，不下载镜像层，也不改动容器。
 */
@Data
public class AgentUpdateCheckVo {

    /** 检查所用的完整镜像（容器实际使用的，含 tag）。 */
    String image;

    /** 镜像 tag（从实际镜像地址解析）。 */
    String tag;

    /** 实际镜像内置的版本号（OCI label），旧镜像可能为 null。 */
    String agentVersion;

    /** 平台自身版本（agent 镜像与平台同 tag 发版），未打包运行时为 null。 */
    String platformVersion;

    /** 注册中心里该 tag 当前指向的顶层 manifest 摘要（与 docker pull 记入 RepoDigests 的一致）。 */
    String remoteDigest;

    /** 本地镜像的摘要（优先 RepoDigests，其次镜像 ID），用于页面展示比较前后。 */
    String localDigest;

    /** 注册中心该 tag 与本地镜像一致，即没有比本地更新的镜像。 */
    boolean digestUpToDate;

    /**
     * agent 镜像内置版本号落后于平台版本。仅默认官方镜像且版本可读时判定；
     * 典型场景是固定了旧版本 tag（如 v4.8.40）：tag 内容没变，但平台已发新版。
     */
    boolean versionOutdated;

    /** 综合结论：摘要与版本都没问题才是最新，true 时无需更新。 */
    boolean upToDate;

    /** 面向用户的结论说明（无需更新 / 可直接更新 / 需先改 tag）。 */
    String message;
}
