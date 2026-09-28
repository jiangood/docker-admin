package io.github.jiangood.docker.admin.entity;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.openadmin.util.annotation.Remark;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.framework.validator.ValidateStartWithLetter;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

@Remark("项目")
@Getter
@Setter
@Entity
@FieldNameConstants
@Table(name = "t_project")
public class Project extends BaseEntity {

    @Remark("组织")
    @ManyToOne
    SysOrg sysOrg;


    @Remark("名称")
    @ValidateStartWithLetter
    @NotNull
    @Column(unique = true)
    String name;

    @Remark("中文名称")
    String cnName;

    @NotNull
    String gitUrl;



    //默认的dockerfile
    @NotNull
    String dockerfile;

    @Remark("构建参数")
    String buildArg;


    @Remark("Webhook 令牌")
    @Column(unique = true)
    String webhookToken;


    String remark;


    @PrePersist
    public void prePersist() {
        if (StrUtil.isBlank(webhookToken)) {
            webhookToken = RandomUtil.randomString(32);
        }
    }


}
