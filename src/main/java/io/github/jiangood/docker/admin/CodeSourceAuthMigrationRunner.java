package io.github.jiangood.docker.admin;

import io.github.jiangood.docker.admin.service.CodeSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 兼容升级：旧「代码源」没有访问方式，令牌存在密码列。
 * 启动时回填为「访问令牌」方式并把令牌搬到令牌列；无待迁移数据时直接跳过（幂等）。
 */
@Component
@RequiredArgsConstructor
public class CodeSourceAuthMigrationRunner implements ApplicationRunner {

    private final CodeSourceService codeSourceService;

    @Override
    public void run(ApplicationArguments args) {
        codeSourceService.backfillLegacyAuth();
    }
}
