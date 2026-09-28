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

    String imageName;
    String imageId;

    String imageUrl;

    /**
     * 构建用的 git tag，同时作为镜像版本号（如 v1.0.1）。
     */
    String tag;

    LocalDateTime completeTime;

    Boolean success;

    String context = "/";
    String dockerfile;


    @Transient
    String logUrl;


    Long timeSpend; // 用时， 毫秒


}
