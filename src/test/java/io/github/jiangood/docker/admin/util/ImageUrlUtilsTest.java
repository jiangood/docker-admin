package io.github.jiangood.docker.admin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageUrlUtilsTest {

    @Test
    void repoNameStripsRegistryHost() {
        assertEquals("ztcn/nginx", ImageUrlUtils.repoNameOf("registry.cn-hangzhou.aliyuncs.com/ztcn/nginx"));
        assertEquals("ztcn/nginx", ImageUrlUtils.repoNameOf("https://registry.cn-hangzhou.aliyuncs.com/ztcn/nginx"));
        assertEquals("foo/bar", ImageUrlUtils.repoNameOf("localhost:5000/foo/bar"));
    }

    @Test
    void repoNameAddsDockerHubLibraryPrefix() {
        assertEquals("library/nginx", ImageUrlUtils.repoNameOf("nginx"));
        assertEquals("ztcn/nginx", ImageUrlUtils.repoNameOf("ztcn/nginx"));
    }

}
