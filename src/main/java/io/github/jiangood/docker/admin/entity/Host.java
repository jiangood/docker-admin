package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.util.annotation.Remark;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
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

    /**
     * 连接方式：本机。无需填写地址，由平台按操作系统自动选择端点
     * （Linux：unix:///var/run/docker.sock，Windows：tcp://localhost:2375），
     * 也可通过 dockerHost 指定自定义 socket 路径（如 rootless）。
     */
    public static final String TYPE_LOCAL = "local";
    /**
     * 连接方式：远程 TCP 端口（tcp://ip:2375）
     */
    public static final String TYPE_TCP = "tcp";
    /**
     * 连接方式：SSH 登录后通过 docker system dial-stdio 连接
     */
    public static final String TYPE_SSH = "ssh";
    /**
     * 旧版本的本机 unix socket 类型，兼容历史数据，保存时统一归一为 {@link #TYPE_LOCAL}
     */
    @Deprecated
    public static final String TYPE_UNIX = "unix";

    @Remark("名称")
    @NotNull
    @Column(unique = true)
    String name;

    @Remark("构建节点")
    Boolean isRunner;

    @Remark("连接方式")
    String connectionType;

    @Remark("dockerHost")
    String dockerHost;

    @Remark("SSH地址")
    String sshHost;

    @Remark("SSH端口")
    Integer sshPort;

    @Remark("SSH用户")
    String sshUser;

    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String sshPassword;

    @Remark("备注")
    String remark;

    @PrePersist
    public void prePersist() {
        if (isRunner == null) {
            isRunner = false;
        }
        if (connectionType == null) {
            connectionType = TYPE_LOCAL;
        }
    }

    /**
     * 是否使用 SSH 方式连接。
     */
    @JsonIgnore
    public boolean isSsh() {
        return TYPE_SSH.equalsIgnoreCase(connectionType);
    }
}
