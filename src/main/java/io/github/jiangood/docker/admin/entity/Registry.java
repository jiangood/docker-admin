package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 镜像注册中心，全局唯一（取最新一条）。
 */
@Remark("镜像注册中心")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_registry")
public class Registry extends BaseEntity {

    @Remark("地址")
    @Column(length = 200)
    String url;

    @Remark("命名空间")
    @Column(length = 200)
    String namespace;

    @Remark("用户名")
    @Column(length = 200)
    String username;

    @Remark("密码")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    String password;

    public String getFullUrl() {
        if (url != null) {
            return url + "/" + namespace;
        }

        return namespace;
    }

    /**
     * 返回给前端的打码密码，真实密码不参与序列化。
     */
    public String getPasswordMasked() {
        return password == null || password.isEmpty() ? "" : "******";
    }

    @Override
    public String toString() {
        return getFullUrl();
    }
}
