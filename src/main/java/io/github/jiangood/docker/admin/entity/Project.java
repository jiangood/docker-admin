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
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 项目：一个从代码仓库构建、产出带版本（tag）镜像的构建定义。
 * <p>
 * 每个项目对应一个镜像仓库地址（registry.url/namespace + name），
 * 构建产生的 tag 即为其镜像标签（{@link ImageTag}）。
 */
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


    @Remark("项目名")
    @ValidateStartWithLetter
    @NotNull
    @Column(unique = true)
    String name;

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


    @Remark("自动配置 Webhook")
    Boolean webhookAuto;

    @Remark("代码仓库侧 Webhook ID")
    String webhookHookId;


    String remark;

    /**
     * 镜像url（不带 tag 的仓库地址），按当前注册中心与项目名计算，不持久化。
     */
    @Remark("镜像地址")
    @Transient
    String imageUrl;


    @PrePersist
    public void prePersist() {
        if (StrUtil.isBlank(webhookToken)) {
            webhookToken = RandomUtil.randomString(32);
        }
    }


}
