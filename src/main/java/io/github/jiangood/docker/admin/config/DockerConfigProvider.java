package io.github.jiangood.docker.admin.config;

import io.github.jiangood.docker.admin.dao.HostRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.util.AgentImageUtils;
import io.github.jiangood.openadmin.framework.config.dynamic.ConfigGroup;
import io.github.jiangood.openadmin.framework.config.dynamic.Configs;
import io.github.jiangood.openadmin.framework.spi.ConfigProvider;
import io.github.jiangood.openadmin.util.dto.Option;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「系统管理 - 系统参数」里的容器相关分组：默认资源、容器运行、主机辅助容器、容器日志。
 * <p>
 * 框架的 {@code ConfigRegistry} 会注入所有 {@link ConfigProvider} Bean，
 * 所以加 {@code @Component} 就会自动出现在系统参数页，无需前端改动。
 * <p>
 * 注意：这里只做声明，不能注入 {@code SysConfigService}（它会反向依赖 ConfigRegistry，
 * 构成构造器循环依赖）；读取逻辑在 {@link DockerSettings}。
 * <p>
 * 默认注册中心 / 默认构建主机原来是项目自己的「设置 - 全局设置」页面，现在用
 * {@link Configs#select(String, String, java.util.function.Supplier)} 动态列出业务表里的选项：
 * 声明阶段不查库，每次打开参数页 / 保存时才求值，所以注入 Repository 是安全的。
 * 旧 {@code t_system_config} 里的值由 {@link LegacySystemConfigMigrator} 一次性搬迁。
 */
@Component
@RequiredArgsConstructor
public class DockerConfigProvider implements ConfigProvider {

    private final RegistryRepository registryRepository;
    private final HostRepository hostRepository;

    @Override
    public List<ConfigGroup> define() {
        return List.of(
                Configs.group("docker-defaults", "默认资源", 45)
                        .description("未显式指定时使用的平台默认注册中心与构建主机")
                        .add(Configs.select(DockerConfigKeys.DEFAULT_REGISTRY_ID, "默认镜像注册中心",
                                        () -> registryRepository.findAll(Sort.by(Sort.Direction.ASC, Registry.Fields.url))
                                                .stream()
                                                .map(r -> new Option(r.getId(), r.getFullUrl()))
                                                .toList())
                                .allowEmpty()
                                .hint("未指定注册中心的镜像操作使用此默认值。选项来自「设置 - 注册中心」，"
                                        + "新增后重新进入本页即可看到；留空表示不设置默认注册中心"))
                        .add(Configs.select(DockerConfigKeys.DEFAULT_BUILD_HOST_ID, "默认构建主机",
                                        () -> hostRepository.findAll(Sort.by(Sort.Direction.ASC, Host.Fields.name))
                                                .stream()
                                                .map(h -> new Option(h.getId(), h.getName()))
                                                .toList())
                                .allowEmpty()
                                .hint("未指定构建主机时使用此主机。选项来自主机列表；"
                                        + "留空表示不设置默认构建主机")),
                Configs.group("docker", "容器运行", 50)
                        .description("容器运行与平台交互的全局默认值，对所有主机上的应用容器生效")
                        .add(Configs.integer(DockerConfigKeys.STOP_TIMEOUT_SECONDS, "停止容器宽限期（秒）")
                                .defaultValue(DockerConfigKeys.DEFAULT_STOP_TIMEOUT_SECONDS)
                                .min(DockerConfigKeys.MIN_STOP_TIMEOUT_SECONDS)
                                .max(DockerConfigKeys.MAX_STOP_TIMEOUT_SECONDS)
                                .hint("部署、停止、重启时先发送 SIGTERM，等待该秒数后仍未退出才 SIGKILL，"
                                        + "等价 docker compose 的 stop_grace_period。"
                                        + "只作用于平台发起的停止；在宿主机上手工执行 docker stop 仍按容器自身配置"
                                        + "（Docker 默认 10 秒）。")),
                Configs.group("docker-agent", "主机辅助容器", 55)
                        .description("主机文件管理 / Docker CLI 使用的辅助容器（docker-admin-agent）")
                        .add(Configs.string(DockerConfigKeys.AGENT_REPOSITORY, "Agent 镜像地址")
                                .defaultValue("")
                                .hint("只填镜像仓库地址，不要带 tag 或 digest，例如 "
                                        + AgentImageUtils.DEFAULT_REPOSITORY
                                        + "；tag 在每台主机部署时选择。留空使用 application.yml 的默认地址。")),
                Configs.group("docker-logs", "容器日志", 60)
                        .description("容器日志时间段查询、下载与回看窗口的全局限制")
                        .add(Configs.integer(DockerConfigKeys.LOG_RANGE_TAIL, "最大行数")
                                .defaultValue(DockerConfigKeys.DEFAULT_LOG_RANGE_TAIL)
                                .min(1)
                                .max(DockerConfigKeys.MAX_LOG_RANGE_TAIL)
                                .hint("按时间段查询和下载时最多返回的行数。"))
                        .add(Configs.integer(DockerConfigKeys.LOG_DOWNLOAD_DAYS, "下载最多天数")
                                .defaultValue(DockerConfigKeys.DEFAULT_LOG_DOWNLOAD_DAYS)
                                .min(1)
                                .max(DockerConfigKeys.MAX_LOG_DOWNLOAD_DAYS)
                                .hint("日志下载最多回溯的天数；未选择时间范围时下载最近 N 天。")));
    }
}
