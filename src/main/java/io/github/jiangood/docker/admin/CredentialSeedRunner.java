package io.github.jiangood.docker.admin;

import cn.hutool.core.collection.CollUtil;
import io.github.jiangood.docker.admin.dao.GitCredentialRepository;
import io.github.jiangood.docker.admin.dao.RegistryRepository;
import io.github.jiangood.docker.admin.entity.GitCredential;
import io.github.jiangood.docker.admin.entity.Registry;
import io.github.jiangood.docker.admin.service.RegistryService;
import io.github.jiangood.docker.config.CfgGitRepo;
import io.github.jiangood.docker.config.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首次启动时把 yml 中的 cfg.registry / cfg.git-repos 导入数据库，
 * 之后以数据库为准，保证老部署平滑迁移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialSeedRunner implements ApplicationRunner {

    private final RegistryRepository registryRepository;
    private final GitCredentialRepository gitCredentialRepository;
    private final Config config;

    @Override
    public void run(ApplicationArguments args) {
        seedRegistry();
        seedGitCredentials();
    }

    private void seedRegistry() {
        if (registryRepository.count() > 0) {
            return;
        }
        Registry registry = RegistryService.fromCfg(config.getRegistry());
        if (registry == null) {
            return;
        }
        registryRepository.save(registry);
        log.info("已从 yml 导入镜像注册中心配置: {}", registry.getFullUrl());
    }

    private void seedGitCredentials() {
        if (gitCredentialRepository.count() > 0) {
            return;
        }
        List<CfgGitRepo> cfgList = config.getGitRepos();
        if (CollUtil.isEmpty(cfgList)) {
            return;
        }
        for (CfgGitRepo cfg : cfgList) {
            GitCredential c = new GitCredential();
            c.setName(cfg.getUrl());
            c.setUrl(cfg.getUrl());
            c.setUsername(cfg.getUsername());
            c.setPassword(cfg.getPassword());
            gitCredentialRepository.save(c);
        }
        log.info("已从 yml 导入 {} 条 Git 凭据", cfgList.size());
    }
}
