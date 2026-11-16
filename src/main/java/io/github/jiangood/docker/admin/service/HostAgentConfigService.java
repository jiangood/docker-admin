package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.HostAgentConfig;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.docker.admin.util.HostRootPolicy;
import io.github.jiangood.openadmin.framework.data.BaseService;
import io.github.jiangood.openadmin.util.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具菜单「主机 Agent」的配置管理：每条配置对应一台主机，保存该入口的
 * 镜像 tag 与挂载目录，是 Agent 部署参数（镜像 tag / 目录）的唯一来源。
 * <p>
 * 真正部署辅助容器仍走 {@link HostAgentService}；部署 / 切换目录成功后由
 * {@link #saveDeployedConfig} 回写实际使用的 tag 与目录，配置与容器保持一致。
 */
@Service
@RequiredArgsConstructor
public class HostAgentConfigService extends BaseService<HostAgentConfig> {

    /** 常用命令的条数与长度上限：JSON 存在同一列里，避免无限膨胀。 */
    private static final int MAX_COMMANDS = 10;
    private static final int MAX_COMMAND_LENGTH = 500;
    private static final int MAX_COMMAND_NAME_LENGTH = 50;

    private final HostService hostService;

    /**
     * 保存配置：新增走 create，编辑走 update；保存前校验主机、归一化 tag 与目录。
     * <p>
     * 必填项（名称/主机）在前端与实体上都校验，这里再兜一次，
     * 因为直接调接口时可能绕过前端校验。
     */
    @Transactional
    public HostAgentConfig saveConfig(HostAgentConfig input, List<String> updateFields) {
        Assert.notNull(input, "缺少 Agent 配置");
        Assert.hasText(input.getHostId(), "请选择主机");

        Host host = hostService.findById(input.getHostId())
                .orElseThrow(() -> new BusinessException("主机不存在"));
        Assert.hasText(host.getName(), "主机名称为空");

        if (StrUtil.isBlank(input.getName())) {
            input.setName(host.getName());
        }
        input.setName(input.getName().trim());
        if (input.getName().length() > 100) {
            throw new BusinessException("名称不能超过 100 个字符");
        }

        // 空值在新增时用默认值，编辑时沿用原配置；再统一归一化与校验
        HostAgentConfig old = findByHostId(input.getHostId());
        String defaultTag = old == null ? AgentImageUtils.DEFAULT_TAG : old.getImageTag();
        String tag = StrUtil.blankToDefault(StrUtil.trimToNull(input.getImageTag()), defaultTag);
        input.setImageTag(AgentImageUtils.normalizeTag(tag));

        String defaultRoot = old == null ? HostRootPolicy.DEFAULT_ROOT : old.getFileRoot();
        String root = StrUtil.blankToDefault(StrUtil.trimToNull(input.getFileRoot()), defaultRoot);
        String normalized = HostRootPolicy.normalizeRoot(root);
        HostRootPolicy.assertValidRoot(normalized);
        input.setFileRoot(normalized);

        // 一个主机只允许一条配置：辅助容器名固定，重复配置会互相覆盖
        if (!isUnique(input.getId(), HostAgentConfig.Fields.hostId, input.getHostId())) {
            throw new BusinessException("主机「" + host.getName() + "」已有 Agent 配置");
        }

        return StrUtil.isBlank(input.getId()) ? create(input) : update(input, updateFields);
    }

    /**
     * 删除配置。只删记录，不动目标主机上已经在运行的辅助容器；
     * 那个容器是普通容器，需要删除时走主机详情的「容器」Tab。
     */
    public void deleteConfig(String id) {
        Assert.hasText(id, "缺少 Agent 配置 id");
        deleteById(id);
    }

    /**
     * 按主机查配置：一台主机最多一条。
     */
    public HostAgentConfig findByHostId(String hostId) {
        if (StrUtil.isBlank(hostId)) {
            return null;
        }
        return findByField(HostAgentConfig.Fields.hostId, hostId);
    }

    /**
     * 读取某主机的常用命令列表；没有 Agent 配置或还没维护过时返回空列表。
     */
    public List<HostAgentConfig.AgentCommand> findCommands(String hostId) {
        HostAgentConfig config = findByHostId(hostId);
        if (config == null) {
            return new ArrayList<>();
        }
        List<HostAgentConfig.AgentCommand> commands = config.getCommands();
        return commands == null ? new ArrayList<>() : commands;
    }

    /**
     * 整体保存某主机的常用命令列表：抽屉里新增 / 修改 / 删除后由前端整体提交，
     * 只动 {@code commands} 这一列，不影响 tag / 目录等其它配置。
     * 保存前统一清洗与校验，返回值以清洗结果为准（前端据此刷新列表）。
     */
    @Transactional
    public List<HostAgentConfig.AgentCommand> saveCommands(String hostId, List<HostAgentConfig.AgentCommand> input) {
        Assert.hasText(hostId, "缺少主机 id");
        HostAgentConfig config = findByHostId(hostId);
        if (config == null) {
            throw new BusinessException("主机没有 Agent 配置，请先在「工具 - 主机 Agent」新增");
        }
        List<HostAgentConfig.AgentCommand> commands = normalizeCommands(input);
        config.setCommands(commands);
        save(config);
        return commands;
    }

    /**
     * 清洗与校验命令列表：trim 名称与正文、去掉空命令条目、限制条数与长度、不允许换行
     * （点击命令是把正文敲进终端，混进换行会直接执行，违背「人工回车」的约定）。
     */
    private List<HostAgentConfig.AgentCommand> normalizeCommands(List<HostAgentConfig.AgentCommand> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        if (input.size() > MAX_COMMANDS) {
            throw new BusinessException("常用命令最多 " + MAX_COMMANDS + " 条");
        }
        List<HostAgentConfig.AgentCommand> commands = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            HostAgentConfig.AgentCommand item = input.get(i);
            if (item == null) {
                continue;
            }
            String command = StrUtil.trimToNull(item.getCommand());
            if (command == null) {
                continue;
            }
            if (command.contains("\n") || command.contains("\r")) {
                throw new BusinessException("第 " + (i + 1) + " 条命令包含换行，请写在同一行");
            }
            if (command.length() > MAX_COMMAND_LENGTH) {
                throw new BusinessException("第 " + (i + 1) + " 条命令超过 " + MAX_COMMAND_LENGTH + " 个字符");
            }
            HostAgentConfig.AgentCommand normalized = new HostAgentConfig.AgentCommand();
            normalized.setName(StrUtil.trimToNull(StrUtil.sub(item.getName(), 0, MAX_COMMAND_NAME_LENGTH)));
            normalized.setCommand(command);
            commands.add(normalized);
        }
        return commands;
    }

    /**
     * 该主机 Agent 实际使用的挂载目录：没有配置时回落到默认 {@code /home}。
     */
    public String resolveFileRoot(String hostId) {
        HostAgentConfig config = findByHostId(hostId);
        return config == null || StrUtil.isBlank(config.getFileRoot())
                ? HostRootPolicy.DEFAULT_ROOT
                : HostRootPolicy.normalizeRoot(config.getFileRoot());
    }

    /**
     * 该主机 Agent 实际使用的镜像 tag：没有配置时回落到 {@code latest}。
     */
    public String resolveImageTag(String hostId) {
        HostAgentConfig config = findByHostId(hostId);
        return config == null || StrUtil.isBlank(config.getImageTag())
                ? AgentImageUtils.DEFAULT_TAG
                : AgentImageUtils.normalizeTag(config.getImageTag());
    }

    /**
     * 部署 / 切换目录成功后记录实际使用的 tag 与目录。
     * <p>
     * 主机还没有 Agent 配置时自动创建一条（例如通过旧接口直接部署辅助容器），
     * 这样工具菜单里能看到这条主机，不会出现「容器在跑、页面没有入口」的情况。
     */
    @Transactional
    public void saveDeployedConfig(Host host, String tag, String root) {
        HostAgentConfig config = findByHostId(host.getId());
        if (config == null) {
            config = new HostAgentConfig();
            config.setHostId(host.getId());
            config.setName(StrUtil.blankToDefault(host.getName(), "主机 Agent"));
            config.setRemark("部署辅助容器时自动创建");
        }
        if (StrUtil.isNotBlank(tag)) {
            config.setImageTag(AgentImageUtils.normalizeTag(tag));
        }
        if (StrUtil.isNotBlank(root)) {
            String normalized = HostRootPolicy.normalizeRoot(root);
            HostRootPolicy.assertValidRoot(normalized);
            config.setFileRoot(normalized);
        }
        save(config);
    }

}
