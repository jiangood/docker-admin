package io.github.jiangood.docker.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * yml 中的框架级配置（cfg.*）。注册中心、代码源等业务配置一律在后台维护，不走配置文件。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cfg")
public class Config {

}
