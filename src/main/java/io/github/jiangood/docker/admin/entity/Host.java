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
     * 连接方式：远程 TCP 端口（tcp://ip[:port]，端口可省，省略时按 80/443 访问；
     * dockerHost 用 {@link #HTTPS_PREFIX} 开头则表示走 TLS）
     */
    public static final String TYPE_TCP = "tcp";
    /**
     * 连接方式：SSH 登录后通过 docker system dial-stdio 连接
     */
    public static final String TYPE_SSH = "ssh";

    /**
     * 远程 TCP 的 TLS 标记：dockerHost 以该前缀开头表示走 https
     * （传输层只认 tcp://，TLS 由 {@code DockerClientManager} 另注入 SSLConfig 实现）。
     */
    public static final String HTTPS_PREFIX = "https://";

    /**
     * 默认请求头名称：docker-api-auth 等代理约定的自定义请求头名。
     */
    public static final String DEFAULT_HEADER_NAME = "Authorization";

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

    /**
     * 自定义请求头名称：代理要求的头名（header name），如 docker-api-auth 的
     * {@code Authorization}、http-tunnel 约定的自定义 key。留空按
     * {@link #DEFAULT_HEADER_NAME} 处理。
     */
    @Remark("自定义请求头")
    String headerName;

    /**
     * 自定义请求头的值：{@link #headerName} 对应的值。名称为 {@code Authorization} 时可只填
     * token（保存时自动补 {@code Bearer } 前缀），其它名称原样发送。
     * <p>
     * 同 {@link #sshPassword}：不回传前端，编辑时留空表示沿用原值。
     */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "auth_header")
    @Remark("请求头值")
    String headerValue;

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

    /**
     * 是否已配置请求头值。
     * <p>
     * {@link #headerValue} 本身以 WRITE_ONLY 回传前端的值为空，这里用一个派生布尔量
     * 供详情页展示「已配置 / 未配置」，不泄露明文。
     */
    public boolean isHeaderConfigured() {
        return headerValue != null && !headerValue.isBlank();
    }

    /**
     * 请求头名称，未配置时回落到 {@link #DEFAULT_HEADER_NAME}（前端回显、建立连接都用它）。
     */
    public String getHeaderName() {
        return headerName == null || headerName.isBlank() ? DEFAULT_HEADER_NAME : headerName.trim();
    }
}
