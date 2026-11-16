package io.github.jiangood.docker.admin.entity;

import io.github.jiangood.docker.admin.entity.converter.AgentCommandListConverter;
import io.github.jiangood.openadmin.framework.data.BaseEntity;
import io.github.jiangood.openadmin.util.annotation.Remark;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机 Agent 配置：工具菜单里的一条「Agent 入口」，绑定一台主机，
 * 并保存该入口期望使用的镜像 tag 与挂载目录。
 * <p>
 * 一台主机只能有一条配置（辅助容器名固定为 {@code docker-admin-agent}，一个主机上只跑一个）。
 * 这里保存的是「期望值」，实际部署状态仍以容器 label 为准；
 * 部署或切换目录成功后会回写本记录，保证下次打开时看到的就是当前容器使用的配置。
 */
@Remark("主机 Agent")
@Entity
@Getter
@Setter
@FieldNameConstants
@Table(name = "t_host_agent_config")
public class HostAgentConfig extends BaseEntity {

    /**
     * 配置名称，默认取主机名。
     */
    @NotBlank
    @Size(max = 100)
    @Remark("名称")
    String name;

    /**
     * 目标主机 id，一台主机只允许一条配置。
     */
    @NotBlank
    @Column(unique = true)
    @Remark("主机")
    String hostId;

    /**
     * 期望使用的 agent 镜像 tag，空表示 latest。
     */
    @Size(max = 128)
    @Remark("镜像 Tag")
    String imageTag;

    /**
     * 期望挂载的宿主机目录，空表示主机上保存的目录（首次 /home）。
     */
    @Size(max = 512)
    @Remark("挂载目录")
    String fileRoot;

    @Size(max = 500)
    @Remark("备注")
    String remark;

    /**
     * 常用命令：终端页「常用命令」抽屉维护，每台主机各存一份（JSON 存 text 列，不单独建表）。
     * 点击某条命令时前端把 {@code command} 原样敲进终端，不自动回车，由用户确认后再执行。
     */
    @Lob
    @Convert(converter = AgentCommandListConverter.class)
    @Remark("常用命令")
    List<AgentCommand> commands = new ArrayList<>();

    /**
     * 单条常用命令：{@code name} 为显示名（可空，空时前端按命令截断展示），
     * {@code command} 是敲进终端的命令正文，不带换行。
     */
    @Data
    public static class AgentCommand {

        @Size(max = 50)
        String name;

        @Size(max = 500)
        String command;
    }

}
