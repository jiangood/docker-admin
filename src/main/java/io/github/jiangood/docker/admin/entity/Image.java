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

/**
 * 镜像：一个从代码仓库构建、带版本（tag）的镜像仓库。
 * <p>
 * 每个镜像对应一个镜像地址（registry.url/namespace + name），构建产生的 tag 即为其版本。
 */
@Remark("镜像")
@Getter
@Setter
@Entity
@FieldNameConstants
@Table(name = "t_image")
public class Image extends BaseEntity {

    @Remark("组织")
    @ManyToOne
    SysOrg sysOrg;


    @Remark("镜像名")
    @ValidateStartWithLetter
    @NotNull
    @Column(unique = true)
    String name;

    @Remark("中文名称")
    String cnName;

    @Remark("代码仓库")
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
