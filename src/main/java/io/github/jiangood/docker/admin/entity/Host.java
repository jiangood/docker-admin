package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.as.common.tools.annotation.Remark;
import io.github.jiangood.as.framework.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * 主机信息
 */
@Remark("主机")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_host")
public class Host extends BaseEntity {

    @Remark("名称")
    @NotNull
    @Column(unique = true)
    String name;

    @Remark("构建节点")
    Boolean isRunner;


    String dockerHost;


    @Remark("备注")
    String remark;

    @PrePersist
    public void prePersist() {
        if(isRunner == null){
            isRunner = false;
        }
    }
}
