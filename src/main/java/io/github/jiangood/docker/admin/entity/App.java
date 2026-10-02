package io.github.jiangood.docker.admin.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.jiangood.docker.admin.entity.converter.AppConfigConverter;
import io.github.jiangood.openadmin.util.annotation.Remark;
import io.github.jiangood.openadmin.framework.data.DBConstants;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.framework.validator.ValidateStartWithLetter;
import io.github.jiangood.openadmin.modules.system.entity.SysOrg;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.ArrayList;
import java.util.List;

@Remark("应用")
@Entity
@Getter
@Setter
@Table(name = "t_app")
@FieldNameConstants
public class App extends BaseEntity {

    @ValidateStartWithLetter
    @NotNull
    @Column(unique = true)
    String name;

    @ManyToOne
    SysOrg sysOrg;



    @Remark("标签")
    String tag;


    @NotNull
    @ManyToOne
    Host host;


    /**
     * 镜像url（不带 tag 的仓库地址），应用运行的镜像所属仓库。
     */
    @Remark("镜像地址")
    @Column(length = 400)
    String imageUrl;

    @Remark("版本")
    @Column(length = 20)
    String imageTag;


    Boolean autoDeploy;



    @Transient
    String logUrl;


    @Lob
    @Convert(converter = AppConfigConverter.class)
    AppConfig config;

    @Remark("备注")
    String remark;

    /**
     * 隧道（http-tunnel）：应用详情页「隧道」标签的配置。
     * 只需开关、域名前缀、端口与所选客户端；完整域名 = 域名前缀 + "." + 所选客户端域名，
     * 即 tunnelPrefix + "." + {@link TunnelClient#getDomain()}，因此这里不保存域名。
     */
    @Remark("隧道开关")
    Boolean tunnelEnabled;

    @Remark("域名前缀")
    String tunnelPrefix;

    @Remark("隧道端口")
    Integer tunnelPort;

    /**
     * 应用隧道使用的客户端，在应用详情页显式选择。
     */
    @Remark("隧道客户端")
    @ManyToOne
    TunnelClient tunnelClient;


   @PrePersist
    public void prePersist() {
        if (autoDeploy == null) {
            autoDeploy = true;
        }
        if (tunnelEnabled == null) {
            tunnelEnabled = false;
        }
        if (config == null) {
            config = new AppConfig();
            config.setNetworkMode("bridge");
        }


    }


    @Data
    public static class AppConfig {


        String cmd; //启动命令

        String extraHosts; // ip映射

        // 主机:容器
        List<PortBinding> ports = new ArrayList<>(); //  - 7100:7100/udp  - 7100:7100/tcp

        /**
         * /var/run/docker.sock:/var/run/docker.sock:ro
         * /var/run/docker.sock:/var/run/docker.sock:rw
         */
        List<BindConfig> binds = new ArrayList<>();


        String environmentYAML;

        /**
         *   host:主机模式（同主机IP）
         *   bridge:桥接（虚拟IP，NAT）
         *   none:  无需网络'
         */
        String networkMode;

        public String getNetworkMode() {
            if (networkMode == null) {
                networkMode = "bridge";
            }
            return networkMode;
        }

        /**
         * 设备请求（GPU 等），对应 Docker API 的 HostConfig.DeviceRequests。
         * 与 docker run --gpus / compose 的 devices 一致，为空表示不请求设备。
         */
        List<DeviceRequest> deviceRequests = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class DeviceRequest {
        String driver;                    // Driver，如 nvidia
        Integer count;                    // Count，-1 表示全部
        List<List<String>> capabilities;  // Capabilities，默认 [["gpu"]]
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class BindConfig {
        String publicVolume;
        String privateVolume;
        Boolean readOnly; // ro, rw

    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class PortBinding {
        Integer publicPort;
        Integer privatePort;
        String protocol;

    }


}
