package io.github.jiangood.docker.config;


import lombok.Data;

@Data
public class Registry {


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
