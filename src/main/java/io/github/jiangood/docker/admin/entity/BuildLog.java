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
     */
    String tag;

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
