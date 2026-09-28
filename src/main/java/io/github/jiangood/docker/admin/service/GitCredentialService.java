package io.github.jiangood.docker.admin.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiangood.docker.admin.dao.GitCredentialRepository;
import io.github.jiangood.docker.admin.entity.GitCredential;
import io.github.jiangood.docker.config.CfgGitRepo;
import io.github.jiangood.docker.config.Config;
import io.github.jiangood.openadmin.framework.data.BaseService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitCredentialService extends BaseService<GitCredential> {

    private final GitCredentialRepository gitCredentialRepository;

    @Resource
    Config config;

    /**
     * 按 url 前缀匹配最合适的凭据：数据库优先，数据库为空时回落到 yml 的 cfg.git-repos。
     */
    public GitCredential findBestByUrl(String gitUrl) {
        if (StrUtil.isBlank(gitUrl)) {
            return null;
        }

        List<GitCredential> list = gitCredentialRepository.findAll();
        if (CollUtil.isNotEmpty(list)) {
            return match(list, gitUrl);
        }

        List<CfgGitRepo> cfgList = config.getGitRepos();
        if (CollUtil.isEmpty(cfgList)) {
            return null;
        }
        List<GitCredential> fallback = new ArrayList<>();
        for (CfgGitRepo cfg : cfgList) {
            GitCredential c = new GitCredential();
            c.setUrl(cfg.getUrl());
            c.setUsername(cfg.getUsername());
            c.setPassword(cfg.getPassword());
            fallback.add(c);
        }
        return match(fallback, gitUrl);
    }

    /**
     * 保存凭据，密码留空时保留原密码。
     */
    @Transactional
    public GitCredential saveCredential(GitCredential input) {
        if (StrUtil.isBlank(input.getId())) {
            return create(input);
        }
        GitCredential old = findById(input.getId()).orElse(null);
        Assert.notNull(old, "凭据不存在");
        if (StrUtil.isBlank(input.getPassword())) {
            input.setPassword(old.getPassword());
        }
        return update(input, null);
    }

    private GitCredential match(List<GitCredential> list, String gitUrl) {
        // url 从长到短，避免短前缀优先匹配；不原地修改传入集合
        List<GitCredential> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt((GitCredential c) -> StrUtil.length(c.getUrl())).reversed());
        for (GitCredential credential : sorted) {
            if (StrUtil.isNotBlank(credential.getUrl()) && gitUrl.startsWith(credential.getUrl())) {
                return credential;
            }
        }
        return null;
    }
}
