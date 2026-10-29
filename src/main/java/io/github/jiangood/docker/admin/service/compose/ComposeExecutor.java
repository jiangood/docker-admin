package io.github.jiangood.docker.admin.service.compose;

import io.github.jiangood.docker.admin.dto.ComposeCapabilityVo;
import io.github.jiangood.docker.admin.dto.ComposeFileContentVo;
import io.github.jiangood.docker.admin.dto.ComposeFileVo;
import io.github.jiangood.docker.admin.dto.ComposeProjectVo;
import io.github.jiangood.docker.admin.entity.Host;

import java.util.List;

/**
 * Compose 管理在执行方式上的抽象。
 * <p>
 * 当前只有一种实现：{@link DockerApiComposeExecutor} —— 平台与主机之间只有 docker API，
 * 没有 shell，因此 compose 命令与文件读写都借助 Docker API 起临时容器完成。
 * <p>
 * 以后若要支持"在主机上直接执行命令"的方式（SSH / 本机），新增一个实现即可，
 * 上层的 {@code HostComposeService} 与接口保持不变。
 */
public interface ComposeExecutor {

    /**
     * 探测主机是否可以做 Compose 管理，以及辅助镜像、daemon 连接是否就绪。
     */
    ComposeCapabilityVo probe(Host host);

    /**
     * 列出主机上已有的 compose 项目（{@code docker compose ls --all}）。
     */
    List<ComposeProjectVo> list(Host host);

    /**
     * 打开指定的项目目录，返回其中的 compose 文件与状态。
     */
    ComposeProjectVo open(Host host, String dir);

    /**
     * 列出项目目录下可编辑的文本文件（compose 文件与 .env）。
     */
    List<ComposeFileVo> files(Host host, String dir);

    /**
     * 读取文件内容（带大小上限）。
     */
    ComposeFileContentVo read(Host host, String path);

    /**
     * 覆盖写入文件，保留原有权限位。
     */
    void write(Host host, String path, String content);

    /**
     * 把内容备份到同目录下（文件名加 {@code suffix}），返回备份文件的绝对路径。
     * <p>
     * 备份文件不落在可编辑文件白名单里，所以单独一个方法。
     */
    String backup(Host host, String path, String content, String suffix);

    /**
     * 校验 compose 文件内容（{@code docker compose -f - config -q}）。
     *
     * @return 校验通过返回 null，否则返回可直接展示的错误信息
     */
    String validate(Host host, String dir, String content);

    /**
     * 在项目目录下执行一条 compose 命令，输出实时写入文件日志。
     *
     * @param args compose 子命令（如 {@code up -d}）
     * @return 容器退出码
     */
    int run(Host host, String dir, List<String> args, String logId);

    /**
     * 拉取辅助镜像到主机（首次使用前的准备动作），进度写入文件日志。
     */
    void pullImage(Host host, String image, String logId);

    /**
     * 结束正在执行的任务：kill 并删除对应的辅助容器。
     */
    void cancel(Host host, String logId);
}
