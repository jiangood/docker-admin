package io.github.jiangood.docker.config;

import lombok.Data;


/**
 * yml 中的 git 仓库凭据（cfg.git-repos），仅作为数据库未配置时的兜底来源。
 */
@Data
public class CfgGitRepo {


    /**
     * url, 支持前缀
     */
    private String url;

    private String username;

    private String password;


}
