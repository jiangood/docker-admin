package io.github.jiangood.docker.admin;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.ImageRepository;
import io.github.jiangood.docker.admin.entity.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为历史镜像补齐 webhook token（新镜像在 @PrePersist 中生成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageWebhookTokenRunner implements ApplicationRunner {

    private final ImageRepository imageRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Image> list = imageRepository.findAll();
        int count = 0;
        for (Image image : list) {
            if (StrUtil.isBlank(image.getWebhookToken())) {
                image.setWebhookToken(RandomUtil.randomString(32));
                imageRepository.save(image);
                count++;
            }
        }
        if (count > 0) {
            log.info("已为 {} 个镜像生成 webhook token", count);
        }
    }
}
