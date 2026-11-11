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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Remark("应用")
@Entity
@Getter
@Setter
@Table(name = "t_app")
@FieldNameConstants
public class App extends BaseEntity {

    /**
     * 定时更新默认检查间隔：每天。
     * <p>
     * 定时更新由平台直接读注册中心（只读 manifest，不下载镜像层），
     * 但 Docker Hub 等公共仓库仍按请求限流，默认每天更稳妥；私有仓库可在页面上调短。
     */
    public static final int DEFAULT_SCHEDULED_UPDATE_INTERVAL_MINUTES = 1440;

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
     * 镜像url（不带 tag 的镜像地址），应用运行的镜像。
     */
    @Remark("镜像地址")
    @Column(length = 400)
    String imageUrl;

    /**
     * 镜像版本。Docker 规范里 tag 最长 128 个字符（如 {@code gogost___gost__latest}），
     * 这里按上限放宽，避免长 tag 在保存应用时报 "Value too long"。
     */
    @Remark("版本")
    @Column(length = 128)
    String imageTag;


    Boolean autoDeploy;


    /**
     * 是否启用定时更新：按 {@link #scheduledUpdateIntervalMinutes} 定时检查镜像仓库，
     * 发现运行中容器的镜像发生变化时自动重新部署。
     * <p>
     * 与 {@link #autoDeploy} 的区别：autoDeploy 由项目构建成功事件触发，
     * 这里由时间触发，适用于不是通过本项目构建的外部镜像。
     * 默认关闭，升级后的存量应用也视为关闭。
     */
    @Remark("定时更新")
    Boolean scheduledUpdate;

    /**
     * 定时更新检查间隔（分钟），默认每天。
     */
    @Remark("定时更新间隔")
    Integer scheduledUpdateIntervalMinutes;

    /**
     * 最近一次定时检查时间。
     */
    @Remark("定时检查时间")
    LocalDateTime scheduledUpdateLastCheckTime;

    /**
     * 最近一次定时检查是否成功；null 表示尚未检查。
     */
    @Remark("定时检查结果")
    Boolean scheduledUpdateLastSuccess;

    /**
     * 最近一次定时检查结果摘要，供页面直接展示（如「已是最新」「发现新版本，开始部署」）。
     */
    @Remark("定时检查摘要")
    @Column(length = 1000)
    String scheduledUpdateLastResult;


    @Transient
    String logUrl;


    @Lob
    @Convert(converter = AppConfigConverter.class)
    AppConfig config;

    @Remark("备注")
    String remark;

    @PrePersist
    public void prePersist() {
        if (autoDeploy == null) {
            autoDeploy = true;
        }
        if (scheduledUpdate == null) {
            scheduledUpdate = false;
        }
        if (scheduledUpdateIntervalMinutes == null) {
            scheduledUpdateIntervalMinutes = DEFAULT_SCHEDULED_UPDATE_INTERVAL_MINUTES;
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


        /**
         * 环境变量（键值对）。
         */
        List<EnvVar> envs = new ArrayList<>();

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


    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class EnvVar {
        String name;
        String value;

    }


}
