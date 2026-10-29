package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * 隧道客户端：一个 http-tunnel 的 {@code ht} 进程（v1.0+；0.x 由平台自动回退兼容）。
 * <p>
 * 客户端进程由各业务主机自行运行（只认 {@code -c/--config} 指定的 {@code ht.toml}，没有
 * {@code --remote/--name/--token/--api-port} 这些命令行参数）。v1.0 起服务端、客户端与中继
 * 合并为同一个程序，管理 API 与访客流量共用 {@code listen_port}，且只有 Host 等于
 * {@code control_host} 的请求才进入管理接口；平台维护的隧道就是进程本地的
 * {@code [[proxies]]}（{@code name}/{@code domain}/{@code local_addr}，平台用应用 id 作 name），
 * 改动会原子写回 {@code ht.toml}，因此客户端所在目录需可写。
 * 平台不部署进程、不生成配置文件，只通过其管理 API 维护本地代理。
 * <p>
 * 应用在详情页「隧道」标签里显式选择本表的客户端，完整域名 = 域名前缀 + "." + 客户端域名，
 * 因此应用不需要关心客户端运行在哪台主机上。
 * <p>
 * 令牌用于客户端管理 API 的 {@code Authorization: Bearer <token>} 认证。
 */
@Remark("隧道客户端")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_tunnel_client")
public class TunnelClient extends BaseEntity {

    /**
     * 平台内的客户端名称，建议与 {@code ht.toml} 的 {@code name} 一致。
     * <p>
     * v1.0 起名称只用于展示，不再参与路由，也不是管理 API 的入参。
     */
    @Remark("客户端名称")
    @Column(unique = true)
    String name;

    /**
     * 客户端管理 API 地址，如 http://192.168.1.10:7777。
     * <p>
     * v1.0 起管理 API 与访客流量共用 {@code ht.toml} 的 {@code listen_port}，没有独立的管理端口。
     */
    @Remark("API 地址")
    String apiUrl;

    /**
     * 客户端管理 API 的 Host，对应 {@code ht.toml} 的 {@code control_host}。
     * <p>
     * v1.0 起管理接口只在 Host 等于 {@code control_host} 时可达；平台通常按 IP 连接客户端，
     * 因此需要单独覆盖 Host。留空表示使用 API 地址里的主机名（要求它本身就等于 control_host）。
     * 老版本（0.x）没有 Host 路由，此字段留空即可。
     */
    @Remark("控制 Host")
    String controlHost;

    /**
     * 客户端域名，应用完整域名 = 域名前缀 + "." + 该域名，如 example.com
     */
    @Remark("域名")
    String domain;

    /**
     * 客户端令牌，对应 {@code ht.toml} 的 {@code token}（leaf/relay 也可用 {@code [upstream].token}）；
     * 客户端管理 API 用它做 {@code Authorization: Bearer} 认证
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String token;

    @Remark("备注")
    String remark;

    public String getTokenMasked() {
        return token == null || token.isBlank() ? "" : "******";
    }

    public String apiUrl() {
        return apiUrl == null ? null : apiUrl.trim();
    }

    public String token() {
        return token == null ? null : token.trim();
    }

    public String controlHost() {
        return controlHost == null ? null : controlHost.trim();
    }

    /**
     * 是否已具备调用客户端管理 API 的条件（API 地址 + 令牌）。
     */
    public boolean configured() {
        return apiUrl() != null && !apiUrl().isBlank()
                && token() != null && !token().isBlank();
    }
}
