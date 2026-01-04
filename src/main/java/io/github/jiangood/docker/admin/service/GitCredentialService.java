package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.config.Config;
import io.github.jiangood.docker.config.GitRepo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class GitCredentialService  {


    @Resource
    Config config;


    public GitRepo findBestByUrl(String gitUrl) {
        List<GitRepo> list = config.getGitRepos();


        // url排序， 从长到短
        Collections.sort(list, (a, b) -> b.getUrl().length() - a.getUrl().length());


        for (GitRepo credential : list) {
            if (gitUrl.startsWith(credential.getUrl())) {
                return credential;
            }

        }

        return null;


    }


}
