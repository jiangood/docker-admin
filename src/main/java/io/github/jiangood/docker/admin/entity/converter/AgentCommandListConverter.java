package io.github.jiangood.docker.admin.entity.converter;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.HostAgentConfig;
import io.github.jiangood.openadmin.util.JsonTool;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 常用命令列表 <-> JSON 字符串（{@code t_host_agent_config.commands} 列）。
 * <p>
 * 解析失败按空列表兜底并记日志：终端页仍能打开，下次在抽屉里保存会用新列表整体覆盖。
 */
@Slf4j
public class AgentCommandListConverter
        implements AttributeConverter<List<HostAgentConfig.AgentCommand>, String> {

    @Override
    public String convertToDatabaseColumn(List<HostAgentConfig.AgentCommand> attribute) {
        if (attribute == null) {
            return null;
        }
        return JsonTool.toJsonQuietly(attribute);
    }

    @Override
    public List<HostAgentConfig.AgentCommand> convertToEntityAttribute(String dbData) {
        if (StrUtil.isBlank(dbData)) {
            return new ArrayList<>();
        }
        try {
            List<HostAgentConfig.AgentCommand> commands =
                    JsonTool.jsonToBeanListQuietly(dbData, HostAgentConfig.AgentCommand.class);
            return commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        } catch (Exception e) {
            log.warn("解析常用命令失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }
}
