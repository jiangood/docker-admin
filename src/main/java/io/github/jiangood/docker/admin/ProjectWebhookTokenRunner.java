package io.github.jiangood.docker.admin;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ProjectRepository;
import io.github.jiangood.docker.admin.entity.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为历史项目补齐 webhook token（新项目在 @PrePersist 中生成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectWebhookTokenRunner implements ApplicationRunner {

    private final ProjectRepository projectRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Project> list = projectRepository.findAll();
        int count = 0;
        for (Project project : list) {
            if (StrUtil.isBlank(project.getWebhookToken())) {
                project.setWebhookToken(RandomUtil.randomString(32));
                projectRepository.save(project);
                count++;
            }
        }
        if (count > 0) {
            log.info("已为 {} 个项目生成 webhook token", count);
        }
    }
}
