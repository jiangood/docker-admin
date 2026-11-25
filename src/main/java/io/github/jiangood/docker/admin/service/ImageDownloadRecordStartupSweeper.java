package io.github.jiangood.docker.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 平台重启后，进程内运行中的镜像下载任务已经消失：补齐记录终态并清理半截文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(10)
public class ImageDownloadRecordStartupSweeper implements ApplicationRunner {

    private final ImageDownloadService service;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int interrupted = service.recoverAfterRestart();
            if (interrupted > 0) {
                log.info("清扫平台重启遗留的镜像下载记录 {} 条", interrupted);
            }
        } catch (Exception e) {
            // 启动清扫失败不能阻止平台启动，遗留记录留待下次启动再次处理。
            log.warn("清扫镜像下载记录失败: {}", e.getMessage());
        }
    }

}
