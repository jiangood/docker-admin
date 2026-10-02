package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.util.annotation.Remark;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * 镜像仓库：一个 registry.url/namespace/name 形式的镜像仓库地址（不含 tag）。
 * <p>
 * 一个仓库下有多条 {@link ImageTag}；项目与镜像仓库通过 imageUrl 相等关联。
 */
@Remark("镜像仓库")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_image_repo")
public class ImageRepo extends BaseEntity {

    /**
     * 镜像url：registry.url/namespace/name，不含 tag。
     */
    @Remark("镜像地址")
    @Column(nullable = false, unique = true, length = 400)
    String imageUrl;

    @Remark("仓库名")
    String name;

    /**
     * 来源：BUILD（项目构建）/ SYNC（镜像同步）/ IMPORT（导入）。
     */
    @Remark("来源")
    String source;

    @Remark("注册中心")
    String registryId;

    @Remark("组织")
    @ManyToOne
    SysOrg sysOrg;

    String remark;

}
