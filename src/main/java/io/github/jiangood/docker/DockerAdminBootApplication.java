package io.github.jiangood.docker;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@Slf4j
public class DockerAdminBootApplication {

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        SpringApplication.run(DockerAdminBootApplication.class, args);
        log.info("启动完成，耗时：{}ms", System.currentTimeMillis() - start);
    }

}
