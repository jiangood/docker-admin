package io.github.jiangood.docker.admin.entity;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * 连接代理：设置菜单里统一维护的代理配置，添加 / 修改主机时可直接选择。
 * <p>
 * 目前主要是 HTTP 代理（CONNECT 隧道 + Basic 认证），也保留 SOCKS5（RFC 1928/1929），
 * 与主机原有的代理实现共用 {@code ProxySettings} / {@code ProxyTunnelSocket}。
 * <p>
 * 主机保存时会把代理地址、端口与账号密码复制到 {@code t_host} 的代理列作为快照；
 * 代理被修改后由 {@code ProxyService} 同步到引用它的主机，代理被删除时若仍被主机使用则拒绝。
 */
@Remark("连接代理")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_proxy")
public class Proxy extends BaseEntity {

    public static final String TYPE_HTTP = "http";
    public static final String TYPE_SOCKS5 = "socks5";

    @Remark("名称")
    @Column(length = 100)
    String name;

    /**
     * 协议：{@code http}（默认）/ {@code socks5}。
     */
    @Remark("协议")
    @Column(length = 20)
    String type = TYPE_HTTP;

    @Remark("代理地址")
    @Column(length = 200)
    String host;

    @Remark("代理端口")
    Integer port;

    @Remark("用户名")
    @Column(length = 200)
    String username;

    /**
     * 代理密码：不回传前端，列表只给 {@link #getPasswordMasked()}。
     */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Remark("密码")
    @Column(length = 500)
    String password;

    @Remark("备注")
    @Column(length = 500)
    String remark;

    /**
     * 排序值，越小越靠前；新增时自动取当前最大值 + 1，列表页可上下移动。
     */
    @Remark("排序")
    Integer sortNo;

    @PrePersist
    public void prePersist() {
        if (StrUtil.isBlank(type)) {
            type = TYPE_HTTP;
        }
        if (sortNo == null) {
            sortNo = 0;
        }
    }

    /**
     * 不含密码的地址，如 {@code http://192.168.1.10:8080}。
     */
    public String getAddress() {
        String scheme = StrUtil.blankToDefault(type, TYPE_HTTP);
        if (StrUtil.isBlank(host)) {
            return scheme + "://";
        }
        return scheme + "://" + host + (port == null ? "" : ":" + port);
    }

    /**
     * 给下拉框 / 列表用的展示名：名称（协议://地址:端口），名称为空时只用地址。
     */
    public String getLabel() {
        String address = getAddress();
        return StrUtil.isBlank(name) ? address : name + "（" + address + "）";
    }

    public String getPasswordMasked() {
        return StrUtil.isBlank(password) ? "" : "******";
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
