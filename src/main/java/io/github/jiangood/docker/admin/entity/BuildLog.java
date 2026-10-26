package io.github.jiangood.docker.admin.entity;


import io.github.jiangood.openadmin.framework.data.DBConstants;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_build_log")
public class BuildLog extends BaseEntity {

    @Lob
    @Column(length = DBConstants.LEN_MAX_VARCHAR)
    String codeMessage;

    String buildHostName;
    String buildHostId;

    String projectName;
    String projectId;

    String imageUrl;

    /**
     * 镜像版本号：tag 构建即所选 tag（如 v1.0.1）；分支构建为「分支名 + 构建时间 + 提交短 id」，
     * 如 {@code main-20261020-1530-1a2b3c4d}。
     * <p>
     * 开启「仅构建 latest」时该版本号不会作为镜像标签推送，仅用于追踪本次构建的代码版本，
     * 实际推送的标签是 latest（见 {@link #latestOnly}）。
     */
    String tag;

    /**
     * 本次构建是否只打并推送 latest（构建时项目设置的快照）。
     * 为 true 时 {@link #tag} 中的版本号不产出版本号标签，历史展示的镜像版本应为 latest。
     */
    Boolean latestOnly;

    /**
     * 构建来源类型：{@code TAG} 按 tag 构建，{@code BRANCH} 按分支最新代码构建。
     * 升级前的老记录为空，可结合 {@link #branch} 判断。
     */
    String refType;

    /**
     * 构建来源的 git 引用名（分支名或 tag 名），用于追溯本次构建用的是哪段源码。
     * 与 {@link #tag} 区分：分支构建的 {@link #tag} 是生成的镜像版本号（含构建时间），不是分支名。
     */
    String refName;

    /**
     * 克隆到的提交短 id（8 位）：tag 构建为 tag 指向的提交，分支构建为分支最新提交。
     */
    String commitId;

    /**
     * 提交时间。
     */
    LocalDateTime commitTime;

    /**
     * 分支构建时使用的分支名；tag 构建时为空。用于构建历史展示与失败重试。
     */
    String branch;

    LocalDateTime completeTime;

    Boolean success;

    String context = "/";
    String dockerfile;


    @Transient
    String logUrl;


    Long timeSpend; // 用时， 毫秒


}
