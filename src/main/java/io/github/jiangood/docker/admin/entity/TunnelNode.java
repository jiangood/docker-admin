package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 隧道节点：一个节点（主机）= 一个 nps 客户端 + 一个 npc 容器。
 * <p>
 * 只有「存在已开启隧道应用」的节点才会被创建，记录用于复用 vkey 与展示状态。
 */
@Remark("隧道节点")
@Entity
@Getter
@Setter
@ToString
@FieldNameConstants
@Table(name = "t_tunnel_node")
public class TunnelNode extends BaseEntity {

    @Remark("主机")
    @ManyToOne
    Host host;

    @Remark("nps 客户端 id")
    Integer npsClientId;

    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String vkey;

    @Remark("容器 id")
    String containerId;

    @Remark("状态")
    String status;

    @Remark("错误")
    String lastError;

    @Remark("最后同步")
    LocalDateTime lastSyncTime;

}
