package io.github.jiangood.docker.admin.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dto.ComposeCapabilityVo;
import io.github.jiangood.docker.admin.dto.ComposeFileContentVo;
import io.github.jiangood.docker.admin.dto.ComposeFileVo;
import io.github.jiangood.docker.admin.dto.ComposeFileWriteRequest;
import io.github.jiangood.docker.admin.dto.ComposeProjectVo;
import io.github.jiangood.docker.admin.dto.ComposeTaskVo;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.service.compose.ComposeAction;
import io.github.jiangood.docker.admin.service.compose.ComposeExecutor;
import io.github.jiangood.docker.admin.service.compose.ComposePaths;
import io.github.jiangood.docker.admin.service.compose.ComposeTaskRegistry;
import io.github.jiangood.docker.admin.service.compose.ComposeTaskRunner;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.openadmin.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 主机详情页的 Compose 管理。
 * <p>
 * 只做三件事：看/改 compose 文件、pull、up、down。项目从 {@code docker compose ls} 来，
 * 没有登记过的目录可以手工打开（前端记住打开过的目录）。
 */
@Service
@RequiredArgsConstructor
public class HostComposeService {

    private final ComposeExecutor executor;
    private final ComposeTaskRegistry taskRegistry;
    private final ComposeTaskRunner taskRunner;
    private final TaskLogRegistry taskLogRegistry;
    private final Config config;

    // ------------------------------------------------------------------ 查询

    public ComposeCapabilityVo capability(Host host) {
        return executor.probe(host);
    }

    public List<ComposeProjectVo> list(Host host) {
        return executor.list(host);
    }

    public ComposeProjectVo open(Host host, String dir) {
        return executor.open(host, dir);
    }

    public ComposeFileContentVo read(Host host, String path) {
        return executor.read(host, path);
    }

    /**
     * 项目目录下可编辑的文件（compose 文件与 .env）。
     */
    public List<ComposeFileVo> files(Host host, String dir) {
        return executor.files(host, dir);
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 保存 compose 文件：冲突检测 → 校验 → 备份 → 覆盖。
     * <p>
     * 校验用 {@code docker compose config -q} 在临时容器里跑，<b>不通过就不落盘</b>，
     * 避免把正在运行的项目改坏；通过后先把旧内容备份成 {@code xxx.bak.时间戳} 再覆盖。
     *
     * @return 备份文件的绝对路径
     */
    public String save(Host host, ComposeFileWriteRequest request) {
        String path = ComposePaths.file(request.getPath());
        String dir = ComposePaths.parentOf(path);
        String content = StrUtil.nullToEmpty(request.getContent());

        if (content.getBytes(StandardCharsets.UTF_8).length > ComposePaths.MAX_FILE_BYTES) {
            throw new BusinessException("文件超过 2MB，请登录主机手工编辑");
        }

        ComposeFileContentVo current = executor.read(host, path);
        Long expected = request.getMtime();
        if (expected != null && current.getMtime() > 0
                && Math.abs(current.getMtime() - expected) > 1000) {
            throw new BusinessException("文件已被其他人修改，请关闭编辑器重新打开后再改");
        }

        if (ComposePaths.isComposeFile(ComposePaths.fileName(path))) {
            String error = executor.validate(host, dir, content);
            if (error != null) {
                throw new BusinessException("配置校验未通过，文件未保存：\n" + error);
            }
        }

        String suffix = ".bak." + DateUtil.format(new Date(), "yyyyMMddHHmmss");
        String backup = executor.backup(host, path, current.getContent(), suffix);
        executor.write(host, path, content);
        return backup;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 触发一次 compose 命令，返回日志标识（前端据此连 WebSocket）。
     * <p>
     * 同一项目已有任务时直接拒绝，不做"新的覆盖旧的"。
     */
    public String run(Host host, String dir, String actionCode) {
        ComposeAction action = ComposeAction.of(actionCode);
        String projectDir = ComposePaths.dir(dir);
        String logId = "compose-" + RandomUtil.randomString(12);

        taskRegistry.start(host.getId(), projectDir, action.getCode(), logId);
        // 请求线程里先标记运行中：前端拿到 logId 后可能立刻连日志 WebSocket，
        // 若等异步线程起来再登记，日志流会误判任务已结束而提前关闭
        taskLogRegistry.start(logId);
        taskRunner.run(logId);
        return logId;
    }

    /**
     * 某项目最近一次执行状态；从未执行过时返回 null。
     */
    public ComposeTaskVo task(Host host, String dir) {
        return taskRegistry.last(host.getId(), ComposePaths.dir(dir));
    }

    /**
     * 取消正在执行的任务：标记取消并结束辅助容器。
     */
    public void cancel(Host host, String logId) {
        ComposeTaskRegistry.Task task = taskRegistry.running(logId);
        if (task == null) {
            throw new BusinessException("任务不存在或已经结束");
        }
        task.cancel();
        executor.cancel(host, logId);
    }

    // ------------------------------------------------------------------ 运行环境

    /**
     * 把辅助镜像拉到主机上，返回日志标识。
     */
    public String prepare(Host host) {
        String image = StrUtil.blankToDefault(config.getComposeHelperImage(), "docker:cli");
        String logId = "compose-prepare-" + RandomUtil.randomString(12);
        taskLogRegistry.start(logId);
        taskRunner.prepare(logId, host.getId(), image);
        return logId;
    }

    /**
     * 辅助镜像地址（前端提示用）。
     */
    public String helperImage() {
        return StrUtil.blankToDefault(config.getComposeHelperImage(), "docker:cli");
    }
}
