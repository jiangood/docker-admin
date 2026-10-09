package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 代码源：托管平台（GitLab/Gitee/GitHub/Gitea）或自定义 git 仓库的访问配置，按地址主机匹配。
 * <p>
 * 访问方式（{@link #authType}）决定使用哪组凭据：账号密码 / 访问令牌 / SSH 私钥。
 */
@Remark("代码源")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_code_source")
public class CodeSource extends BaseEntity {

    @Remark("名称")
    @Column(length = 100)
    String name;

    @Remark("类型")
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    CodeSourceType type = CodeSourceType.CUSTOM;

    @Remark("访问方式")
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    CodeSourceAuthType authType = CodeSourceAuthType.TOKEN;

    @Remark("地址")
    @NotBlank
    @Column(length = 500)
    String url;

    @Remark("用户名")
    @Column(length = 200)
    String username;

    @Remark("密码")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    String password;

    @Remark("访问令牌")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    String token;

    @Remark("SSH 私钥")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 4000)
    String privateKey;

    @Remark("私钥口令")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 200)
    String privateKeyPassphrase;

    /**
     * 返回给前端的打码密码，真实密码不参与序列化。
     */
    public String getPasswordMasked() {
        return mask(password);
    }

    public String getTokenMasked() {
        return mask(token);
    }

    public String getPrivateKeyMasked() {
        return mask(privateKey);
    }

    public String getPrivateKeyPassphraseMasked() {
        return mask(privateKeyPassphrase);
    }

    private static String mask(String value) {
        return value == null || value.isEmpty() ? "" : "******";
    }
}
