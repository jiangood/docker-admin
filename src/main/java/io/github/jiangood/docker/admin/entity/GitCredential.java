package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * git 仓库凭据，按 url 前缀匹配。
 */
@Remark("Git凭据")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_git_credential")
public class GitCredential extends BaseEntity {

    @Remark("名称")
    @Column(length = 100)
    String name;

    @Remark("仓库地址前缀")
    @NotBlank
    @Column(length = 500)
    String url;

    @Remark("用户名")
    @Column(length = 200)
    String username;

    @Remark("密码/令牌")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    String password;

    /**
     * 返回给前端的打码密码，真实密码不参与序列化。
     */
    public String getPasswordMasked() {
        return password == null || password.isEmpty() ? "" : "******";
    }
}
