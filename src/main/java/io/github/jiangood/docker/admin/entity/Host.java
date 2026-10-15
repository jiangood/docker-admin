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

    @Remark("名称")
    @NotNull
    @Column(unique = true)
    String name;

    /**
     * 主机 IP 地址：隧道客户端转发目标使用。
     * 目标地址 = 该 IP + 应用映射到主机侧的端口，需为隧道客户端所在机器可访问的地址。
     * <p>
     * 不加 {@code @NotNull}：Hibernate 会把 Bean Validation 的 not-null 落到 DDL 上，
     * 给已有数据的表新增 NOT NULL 列会失败（H2 会拒绝复制含 NULL 的行），导致列建不出来。
     * 必填在 {@code HostService#saveHost} 与前端表单校验。
     */
    @Remark("IP地址")
    String ip;

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
