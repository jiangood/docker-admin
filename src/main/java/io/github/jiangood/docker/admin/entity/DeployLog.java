package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    /**
     * 触发方式：手动部署、配置变更、构建成功自动部署等。更早的记录没有该字段，为空。
     * <p>
     * 显式声明为 VARCHAR：Hibernate 6.2+ 默认把 {@code @Enumerated(STRING)} 映射成数据库原生 ENUM，
     * 而 {@code ddl-auto=update} 不会给已有 ENUM 列补充新取值，新增枚举值（如 SCHEDULE）后
     * 老库插入部署记录会被数据库直接拒绝。老库的列由 {@code DeployLogTriggerTypeMigration} 改成 VARCHAR。
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 32)
    DeployTrigger triggerType;

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
