package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.Date;

@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_deploy_log")
public class DeployLog extends BaseEntity {


    String appId;
    String appName;

    Date completeTime;

    Boolean success;




}
