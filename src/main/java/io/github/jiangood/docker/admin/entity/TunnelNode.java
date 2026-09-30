package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 隧道节点：一个 frpc 客户端，直接复用平台已有的主机（{@link Host}）作为部署目标。
 * <p>
 * 平台通过 docker-java 在该主机上以 host 网络部署 frpc 容器，配置由平台按该节点上的
 * 隧道（{@link Tunnel}）生成，经 {@code docker cp} 写入容器后启动。
 */
@Remark("隧道节点")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_tunnel_node")
public class TunnelNode extends BaseEntity {

    @Remark("节点名称")
    @Column(unique = true)
    String name;

    @Remark("主机")
    @NotNull
    @ManyToOne
    @ToString.Exclude
    Host host;

    @Remark("备注")
    String remark;

    @Remark("frpc 容器")
    String containerId;

    @Remark("错误")
    String lastError;

    @Remark("最后部署")
    LocalDateTime lastDeployTime;

}
