package io.github.jiangood.docker.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "cfg")
public class Config {

    private CfgRegistry registry;

    private List<CfgGitRepo> gitRepos;

}
