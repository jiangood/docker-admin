package io.github.jiangood.docker.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时清扫遗留的 RUNNING 任务记录：平台重启后进程内任务都已消失，
 * 进程外的数据库记录要标记为失败，任务中心才不会永远显示「运行中」。
 */
@Component
@RequiredArgsConstructor
public class TaskLogStartupSweeper implements ApplicationRunner {

    private final TaskLogService taskLogService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            taskLogService.markInterrupted();
        } catch (Exception e) {
            // 清扫失败不影响启动，下次重启再补
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("清扫遗留任务记录失败: {}", e.getMessage());
        }
    }
}
