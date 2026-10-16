package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_deploy_log")
public class DeployLog extends BaseEntity {


    String appId;
    String appName;

    /**
     * 本次部署的镜像版本（tag），发起部署时的快照，便于在部署列表里区分各次部署。
     */
    String imageTag;

    LocalDateTime completeTime;

    Boolean success;

    /**
     * 本次部署是否已生成独立日志文件。更早版本把同一应用的多次部署日志合并写在
     * {@code appId.log} 里，没有独立文件，前端据此给出提示而非空白日志。
     */
    @Transient
    Boolean logExists;

    /**
     * 部署耗时（毫秒），由开始 / 完成时间算出，仅列表展示用。
     */
    @Transient
    Long timeSpend;




}
