package io.github.jiangood.docker.config;


import lombok.Data;

/**
 * yml 中的镜像注册中心配置（cfg.registry），仅作为数据库未配置时的兜底来源。
 */
@Data
public class CfgRegistry {


    private String url;


    private String namespace;


    private String username;


    private String password;


    public String getFullUrl() {
        if (url != null) {
            return url + "/" + namespace;
        }

        return namespace;
    }


    @Override
    public String toString() {
        return getFullUrl();
    }


}
