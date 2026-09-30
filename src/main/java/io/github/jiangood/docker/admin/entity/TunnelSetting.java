package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 * 隧道设置（全局唯一，取最新一条，用法同 {@link Registry}）。
 * <p>
 * 平台据此生成 {@code frps.toml} / {@code frpc.toml}：frps 服务端由用户在主机上手动部署，
 * 页面只提供配置与 docker 命令；各节点的 frpc 由平台按节点主机部署，新增 / 修改 / 删除隧道时
 * 会重新生成所属节点的 {@code frpc.toml} 并重建该 frpc 容器。
 */
@Remark("隧道设置")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_tunnel_setting")
public class TunnelSetting extends BaseEntity {

    public static final String DEFAULT_FRPS_IMAGE = "ghcr.io/jiangood/frps";
    public static final String DEFAULT_FRPC_IMAGE = "ghcr.io/jiangood/frpc";

    /**
     * frps 容器名（单实例，全局唯一），用于页面生成的手动部署命令。
     */
    public static final String FRPS_CONTAINER = "docker-admin-frps";

    /**
     * 客户端与访客访问 frps 的地址（frps 主机的公网 IP 或域名）
     */
    @Remark("连接地址")
    String frpsAddr;

    /**
     * frpc 连接 frps 的端口，frps.toml 的 bindPort
     */
    @Remark("绑定端口")
    Integer bindPort;

    /**
     * frps 对外提供 HTTP 域名路由的端口，frps.toml 的 vhostHTTPPort
     */
    @Remark("HTTP 端口")
    Integer vhostHttpPort;

    /**
     * 域名后缀，隧道访问地址为 子域名.域名后缀
     */
    @Remark("域名后缀")
    String subDomainHost;

    /**
     * frps / frpc 共用的鉴权 token
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String authToken;

    /**
     * frpc 是否用 TLS 连接 frps（transport.tls.enable），跨境 / 受限链路建议开启
     */
    @Remark("传输加密")
    Boolean transportTls;

    @Remark("frps 镜像")
    String frpsImage;

    @Remark("frpc 镜像")
    String frpcImage;

    public String authToken() {
        return authToken == null ? null : authToken.trim();
    }

    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }

    public String getAuthTokenMasked() {
        return hasAuthToken() ? "******" : "";
    }

    public String frpsAddr() {
        return frpsAddr == null ? null : frpsAddr.trim();
    }

    public String subDomainHost() {
        return subDomainHost == null ? null : subDomainHost.trim();
    }

    public int bindPort() {
        return port(bindPort, 7000);
    }

    public int vhostHttpPort() {
        return port(vhostHttpPort, 80);
    }

    public boolean transportTls() {
        return transportTls == null || transportTls;
    }

    public String frpsImage() {
        return image(frpsImage, DEFAULT_FRPS_IMAGE);
    }

    public String frpcImage() {
        return image(frpcImage, DEFAULT_FRPC_IMAGE);
    }

    /**
     * 是否已具备使用条件（连接地址 + 域名后缀）。
     */
    public boolean configured() {
        return frpsAddr() != null && !frpsAddr().isBlank()
                && subDomainHost() != null && !subDomainHost().isBlank();
    }

    private static int port(Integer v, int def) {
        return v == null || v <= 0 ? def : v;
    }

    private static String image(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    @PrePersist
    public void prePersist() {
        if (bindPort == null) {
            bindPort = 7000;
        }
        if (vhostHttpPort == null) {
            vhostHttpPort = 80;
        }
        if (transportTls == null) {
            transportTls = true;
        }
        if (frpsImage == null || frpsImage.isBlank()) {
            frpsImage = DEFAULT_FRPS_IMAGE;
        }
        if (frpcImage == null || frpcImage.isBlank()) {
            frpcImage = DEFAULT_FRPC_IMAGE;
        }
    }

}
