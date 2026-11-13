package io.github.jiangood.docker.admin.service;

import cn.hutool.core.util.RandomUtil;
import io.github.jiangood.docker.admin.entity.TaskLog;
import io.github.jiangood.docker.admin.websocket.TaskLogRegistry;
import io.github.jiangood.docker.base.FileLog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 长任务统一接入点：登记任务记录、绑定日志文件、注册运行状态，收尾时补全结果。
 * <p>
 * 用法（调用方自己负责 {@code @Async}，本类方法都在当前线程执行）：
 * <pre>
 * // 请求线程：校验参数后登记任务，立即把 logId 返回给前端打开实时日志
 * String logId = longTaskRunner.start("image-sync", "同步 nginx → 阿里云", "image-sync:view");
 * SpringUtil.getBean(XxxService.class).asyncRun(logId, ...);
 *
 * // {@code @Async} 方法里：执行并自动收尾
 * public void asyncRun(String logId, ...) {
 *     longTaskRunner.execute(logId, () -> { ...实际工作，逐行 FileLog 写日志... });
 * }
 * </pre>
 * 收尾顺序与 {@link ImageSyncService} 一致：先写库（前端刷新即可拿到终态），
 * 再从 {@link TaskLogRegistry} 移除（日志 WS 检测到结束、推完尾巴后关闭连接）。
 * <p>
 * 日志写法统一用 {@link FileLog#logger()}：本类执行期间线程已绑定 logId，
 * {@code fileLog.info(...)} 会进入任务的日志文件；未绑定时不影响全局日志。
 */
@Service
@Slf4j
public class LongTaskRunner {

    @Resource
    private TaskLogService taskLogService;

    @Resource
    private TaskLogRegistry taskLogRegistry;

    /**
     * 一次长任务的工作体。异常由 {@link #execute} 统一记入日志与任务记录。
     */
    @FunctionalInterface
    public interface TaskWork {

        void run() throws Exception;
    }

    /**
     * 登记一个长任务：生成 logId（类型前缀 + 随机串，即日志文件名）并落库 RUNNING 记录。
     *
     * @param type       任务类型，如 agent-deploy / image-sync
     * @param title      人类可读描述，任务中心展示
     * @param permission 查看该任务实时日志所需的权限码（WS 握手拦截器校验）
     * @return logId，前端用它打开 {@code /admin/ws/task-log/{logId}}
     */
    public String start(String type, String title, String permission) {
        String logId = type + "-" + RandomUtil.randomString(16);
        taskLogService.start(logId, type, title, permission, taskLogService.currentTriggerUser());
        taskLogRegistry.start(logId);
        return logId;
    }

    /**
     * 执行并收尾。内部任务异常只记入任务记录与日志文件，不向外抛——
     * 异步线程的异常没有调用方，抛出去只会污染线程池日志。
     */
    public void execute(String logId, TaskWork work) {
        // start() 已登记过；这里再登记一次兜底（如任务记录由业务自行创建的场景）
        taskLogRegistry.start(logId);
        FileLog.bind(logId);
        boolean success = false;
        String error = null;
        try {
            work.run();
            success = true;
        } catch (Exception e) {
            error = e.getMessage();
            FileLog.logger().error("任务失败: {}", error, e);
        } finally {
            // 先写库再结束任务：日志 WS 断开后前端刷新列表即可拿到终态
            try {
                taskLogService.finish(logId, success, error);
            } catch (Exception e) {
                log.warn("写入任务记录失败: logId={} {}", logId, e.getMessage());
            }
            FileLog.unbind();
            taskLogRegistry.finish(logId);
        }
    }
}
