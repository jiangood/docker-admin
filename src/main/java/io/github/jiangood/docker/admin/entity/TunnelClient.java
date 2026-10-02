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
 * 隧道客户端：一个 http-tunnel client 进程。
 * <p>
 * 客户端进程由各业务主机自行运行：
 * {@code http-tunnel client --remote <服务端> --name <名> --token <令牌> --api-port <管理端口>}。
 * 平台不部署、不生成配置文件，只通过客户端自身的隧道管理 API（{@code --api-port}）维护其隧道
 * （域名 → 目标地址）。
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
     * 客户端名称，与客户端进程 {@code --name} 一致
     */
    @Remark("客户端名称")
    @Column(unique = true)
    String name;

    /**
     * 客户端自身的隧道管理 API 地址，如 http://192.168.1.10:2336，
     * 对应启动命令的 {@code --api-port}
     */
    @Remark("API 地址")
    String apiUrl;

    /**
     * 客户端域名，应用完整域名 = 域名前缀 + "." + 该域名，如 example.com
     */
    @Remark("域名")
    String domain;

    /**
     * 客户端令牌，与客户端进程 {@code --token} 一致；客户端管理 API 也用它认证
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

    /**
     * 是否已具备调用客户端管理 API 的条件（API 地址 + 令牌）。
     */
    public boolean configured() {
        return apiUrl() != null && !apiUrl().isBlank()
                && token() != null && !token().isBlank();
    }
}
