package io.github.jiangood.docker.admin;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.AppRepository;
import io.github.jiangood.docker.admin.entity.App;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为历史应用补齐 webhook token（新应用在 {@code @PrePersist} 中生成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppWebhookTokenRunner implements ApplicationRunner {

    private final AppRepository appRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<App> list = appRepository.findAll();
        int count = 0;
        for (App app : list) {
            if (StrUtil.isBlank(app.getWebhookToken())) {
                app.setWebhookToken(RandomUtil.randomString(32));
                appRepository.save(app);
                count++;
            }
        }
        if (count > 0) {
            log.info("已为 {} 个应用生成 webhook token", count);
        }
    }
}
