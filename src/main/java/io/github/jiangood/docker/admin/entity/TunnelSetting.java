package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDateTime;

/**
 * 隧道设置（全局唯一，取最新一条，用法同 {@link Registry}）。
 * <p>
 * nps 的监听端口写在 nps.conf 里，平台生成 conf 后通过 docker cp 写入容器；
 * 节点通过 bridgePort（明文）或 tlsBridgePort（TLS）连接。
 */
@Remark("隧道设置")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_tunnel_setting")
public class TunnelSetting extends BaseEntity {

    /**
     * 节点桥接方式：明文桥接（bridge_port）
     */
    public static final String BRIDGE_PLAIN = "plain";
    /**
     * 节点桥接方式：TLS 桥接（tls_bridge_port），跨境/受限链路推荐
     */
    public static final String BRIDGE_TLS = "tls";

    public static final String DEFAULT_NPS_IMAGE = "yisier1/nps";
    public static final String DEFAULT_NPC_IMAGE = "yisier1/npc";
    public static final String DEFAULT_CONF_VOLUME = "docker-admin-nps-conf";

    @Remark("nps 主机")
    @ManyToOne
    Host host;

    @Remark("连接地址")
    String npsAddr;

    @Remark("HTTP 代理端口")
    Integer httpProxyPort;

    @Remark("明文桥接端口")
    Integer bridgePort;

    @Remark("TLS 桥接端口")
    Integer tlsBridgePort;

    @Remark("Web 后台端口")
    Integer webPort;

    @Remark("域名后缀")
    String subDomainHost;

    @Remark("Web 用户名")
    String webUsername;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String webPassword;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String authKey;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String authCryptKey;

    @Remark("nps 镜像")
    String npsImage;

    @Remark("npc 镜像")
    String npcImage;

    @Remark("配置卷")
    String confVolume;

    @Remark("节点桥接方式")
    String nodeBridgeMode;

    @Lob
    @Remark("nps.conf 原文")
    String npsConf;

    @Remark("配置哈希")
    String confHash;

    String npsContainerId;

    String npsStatus;

    String lastError;

    LocalDateTime lastDeployTime;

    @Remark("总开关")
    Boolean enabled;

    public String getWebPasswordMasked() {
        return webPassword == null || webPassword.isEmpty() ? "" : "******";
    }

    public String getAuthKeyMasked() {
        return authKey == null || authKey.isEmpty() ? "" : "******";
    }

    public boolean isBridgeTls() {
        return BRIDGE_PLAIN.equalsIgnoreCase(nodeBridgeMode) ? false : true;
    }

    public int intOrDefault(Integer v, int def) {
        return v == null || v <= 0 ? def : v;
    }

    public int httpProxyPort() {
        return intOrDefault(httpProxyPort, 80);
    }

    public int bridgePort() {
        return intOrDefault(bridgePort, 8024);
    }

    public int tlsBridgePort() {
        return intOrDefault(tlsBridgePort, 443);
    }

    public int webPort() {
        return intOrDefault(webPort, 8081);
    }

    public String npsImage() {
        return npsImage == null || npsImage.isBlank() ? DEFAULT_NPS_IMAGE : npsImage.trim();
    }

    public String npcImage() {
        return npcImage == null || npcImage.isBlank() ? DEFAULT_NPC_IMAGE : npcImage.trim();
    }

    public String confVolume() {
        return confVolume == null || confVolume.isBlank() ? DEFAULT_CONF_VOLUME : confVolume.trim();
    }

    public String webUsername() {
        return webUsername == null || webUsername.isBlank() ? "admin" : webUsername.trim();
    }

    /**
     * 节点实际使用的桥接端口。
     */
    public int nodeBridgePort() {
        return isBridgeTls() ? tlsBridgePort() : bridgePort();
    }

    /**
     * 是否已具备部署条件。
     */
    public boolean deployable() {
        return host != null && host.getId() != null
                && npsAddr != null && !npsAddr.isBlank()
                && subDomainHost != null && !subDomainHost.isBlank();
    }

    @PrePersist
    public void prePersist() {
        if (enabled == null) {
            enabled = false;
        }
        if (bridgePort == null) {
            bridgePort = 8024;
        }
        if (tlsBridgePort == null) {
            tlsBridgePort = 443;
        }
        if (webPort == null) {
            webPort = 8081;
        }
        if (webUsername == null || webUsername.isBlank()) {
            webUsername = "admin";
        }
        if (nodeBridgeMode == null || nodeBridgeMode.isBlank()) {
            nodeBridgeMode = BRIDGE_TLS;
        }
        if (confVolume == null || confVolume.isBlank()) {
            confVolume = DEFAULT_CONF_VOLUME;
        }
        if (npsImage == null || npsImage.isBlank()) {
            npsImage = DEFAULT_NPS_IMAGE;
        }
        if (npcImage == null || npcImage.isBlank()) {
            npcImage = DEFAULT_NPC_IMAGE;
        }
    }

}
