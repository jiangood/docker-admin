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
 * 每个项目对应一个镜像地址（registry.url/namespace + name），
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

    /**
     * 构建上下文：相对代码仓库根目录的目录，留空或 "/" 表示仓库根目录。
     * <p>
     * {@link #dockerfile} 仍按仓库根目录解析，但必须位于该上下文目录内；
     * 用于 Dockerfile 与构建产物不在仓库根目录的项目（如 {@code admin/Dockerfile}）。
     */
    @Remark("构建上下文")
    String context;

    @Remark("构建参数")
    String buildArg;

    /**
     * 目标注册中心 id；为空时使用系统默认注册中心。
     * 在项目上配置一次即可，后续手动构建与 Webhook 触发都沿用该值。
     */
    @Remark("目标注册中心")
    String registryId;


    @Remark("Webhook 令牌")
    @Column(unique = true)
    String webhookToken;


    @Remark("自动配置 Webhook")
    Boolean webhookAuto;

    @Remark("代码仓库侧 Webhook ID")
    String webhookHookId;

    @Remark("分支推送自动构建")
    Boolean webhookBranch;

    @Remark("分支推送构建的分支")
    String webhookBranches;

    /**
     * 构建结束（成功或失败）后是否发送邮件通知。
     * 收件人为项目创建人（{@link #createUser}）在用户管理中配置的邮箱。
     */
    @Remark("邮件通知")
    Boolean notifyEmail;

    /**
     * 仅构建 latest：每次构建只打并推送 latest 标签，不产出版本号标签。
     * <p>
     * 用于节省磁盘：旧构建的镜像不再被版本号引用，会变成 dangling 镜像，可被常规清理回收；
     * 注册中心也不再累积历史版本 manifest。代价是无法按版本号回滚或固定部署。
     * 为空或 false 时保持原有行为（版本号 + latest 双标签）。
     */
    @Remark("仅构建 latest")
    Boolean latestOnly;


    String remark;

    /**
     * 镜像url（不带 tag 的仓库地址），按当前注册中心与项目名计算，不持久化。
     */
    @Remark("镜像地址")
    @Transient
    String imageUrl;

    /**
     * 项目创建人邮箱，仅用于详情页展示与提示，不持久化。
     */
    @Remark("创建人邮箱")
    @Transient
    String createUserEmail;


    @PrePersist
    public void prePersist() {
        if (StrUtil.isBlank(webhookToken)) {
            webhookToken = RandomUtil.randomString(32);
        }
    }


}
