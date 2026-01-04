package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.sa.common.tools.annotation.Remark;
import io.github.jiangood.sa.framework.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;


@Remark("应用分组")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_app_group")
public class AppGroup extends BaseEntity {

    @Remark("名称")
    private String name;

    @Column(unique = true)
    @Remark("编码")
    private String code;

    @Remark("排序")
    private Integer seq;

}
