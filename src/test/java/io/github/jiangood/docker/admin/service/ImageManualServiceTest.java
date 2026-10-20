package io.github.jiangood.docker.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageManualServiceTest {

    @Test
    void urlDropsSchemeAndTrailingSlash() {
        assertEquals("registry.cn-hangzhou.aliyuncs.com/ztcn/nginx",
                ImageManualService.normalizeUrl(" https://registry.cn-hangzhou.aliyuncs.com/ztcn/nginx/ "));
        assertEquals("ztcn/nginx", ImageManualService.normalizeUrl("ztcn/nginx"));
    }

    @Test
    void tagInUrlIsSplitOut() {
        assertEquals("registry.cn-hangzhou.aliyuncs.com/ztcn/nginx",
                ImageManualService.normalizeUrl("registry.cn-hangzhou.aliyuncs.com/ztcn/nginx:1.0.0"));
        // 端口里的冒号不能被当成 tag
        assertEquals("harbor.example.com:8443/ztcn/nginx",
                ImageManualService.normalizeUrl("harbor.example.com:8443/ztcn/nginx"));
    }

    @Test
    void tagsAreDeduplicatedAndSplit() {
        assertEquals(List.of("1.0.0", "1.0.1", "latest"),
                ImageManualService.normalizeTags(List.of("1.0.0", "1.0.0, 1.0.1", " latest "), null));
        assertEquals(List.of("2.0.0", "1.0.0"),
                ImageManualService.normalizeTags(List.of("1.0.0"), "2.0.0"));
    }

    @Test
    void invalidTagsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageManualService.normalizeTags(List.of("bad tag!"), null));
    }

}
