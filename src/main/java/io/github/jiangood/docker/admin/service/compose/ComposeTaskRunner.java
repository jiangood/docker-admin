package io.github.jiangood.docker.admin.service.compose;

import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.HostService;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Compose 命令的后台执行线程。
 * <p>
 * 独立成一个 Bean（而不是放在 {@code HostComposeService} 里自调用），否则 {@code @Async} 不生效。
 * 所有输出写进 {@code ${logging.file.path}/{logId}.log}，前端用 WebSocket 实时查看。
 */
@Slf4j
@Service
public class ComposeTaskRunner {

    private static final Logger fileLog = FileLog.logger();

    @Resource
    private HostService hostService;

    @Resource
    private ComposeExecutor executor;

    @Resource
    private ComposeTaskRegistry registry;

    @Resource
    private TaskLogRegistry taskLogRegistry;

    /**
     * 执行一次 compose 命令。异常只写日志，不向外抛。
     */
    @Async
    public void run(String logId) {
        ComposeTaskRegistry.Task task = registry.running(logId);
        if (task == null) {
            return;
        }
        FileLog.bind(logId);
        try {
            Host host = hostService.findById(task.getHostId()).orElse(null);
            if (host == null) {
                registry.finish(task, "FAILED", null, "主机不存在");
                fileLog.error("主机不存在：{}", task.getHostId());
                return;
            }

            ComposeAction action = ComposeAction.of(task.getAction());
            fileLog.info("主机：{}（{}）", host.getName(), StrUtil.blankToDefault(host.getDockerHost(), "本机"));
            fileLog.info("项目目录：{}", task.getDir());
            fileLog.info("执行：docker compose {}", String.join(" ", action.getArgs()));
            fileLog.info("说明：命令在主机上的临时容器里执行，操作的是同一个 docker daemon");

            int code = executor.run(host, task.getDir(), action.getArgs(), logId);

            if (task.isCancelled()) {
                registry.finish(task, "CANCELLED", code, "已取消");
                fileLog.warn("任务已取消（退出码 {}）", code);
            } else if (code == 0) {
                registry.finish(task, "SUCCESS", 0, null);
                fileLog.info("执行完成");
            } else {
                registry.finish(task, "FAILED", code, "命令退出码 " + code + "，详见日志");
                fileLog.error("执行失败，退出码 {}", code);
            }
        } catch (Exception e) {
            fileLog.error("执行失败：{}", e.getMessage());
            fileLog.debug("执行失败详情", e);
            if (task.isCancelled()) {
                // 取消时容器被强杀，wait 可能直接报错，这里按取消收尾
                registry.finish(task, "CANCELLED", null, "已取消");
            } else {
                registry.finish(task, "FAILED", null, StrUtil.blankToDefault(e.getMessage(), "执行失败"));
            }
        } finally {
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }

    /**
     * 把辅助镜像拉到主机上（首次使用前的准备动作）。
     */
    @Async
    public void prepare(String logId, String hostId, String image) {
        FileLog.bind(logId);
        try {
            Host host = hostService.findById(hostId).orElse(null);
            if (host == null) {
                fileLog.error("主机不存在：{}", hostId);
                return;
            }
            fileLog.info("主机：{}（{}）", host.getName(), StrUtil.blankToDefault(host.getDockerHost(), "本机"));
            executor.pullImage(host, image, logId);
        } catch (Exception e) {
            fileLog.error("准备运行环境失败：{}", e.getMessage());
            fileLog.debug("准备运行环境失败详情", e);
        } finally {
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }
}
