package io.github.jiangood.docker.admin.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionUtilsTest {

    private static List<String> sortDesc(String... tags) {
        List<String> list = new ArrayList<>(List.of(tags));
        list.sort(VersionUtils.VERSION_DESC);
        return list;
    }

    @Test
    void numericSegmentsComparedByValue() {
        // 关键：v1.10.0 必须排在 v1.9.0 之前，纯字符串倒序得不到这个结果
        assertEquals(List.of("v1.10.0", "v1.9.0", "v1.2.0", "v0.0.1"),
                sortDesc("v1.2.0", "v1.10.0", "v1.9.0", "v0.0.1"));
    }

    @Test
    void nonNumericTagGoesLast() {
        assertEquals(List.of("v1.0.0", "latest"), sortDesc("latest", "v1.0.0"));
    }

    @Test
    void prereleaseRanksBelowRelease() {
        assertEquals(List.of("v1.9.0", "v1.9.0-rc1"), sortDesc("v1.9.0-rc1", "v1.9.0"));
    }

    @Test
    void dateLikeTagsSortedNumerically() {
        assertEquals(List.of("20240101", "20231201"), sortDesc("20231201", "20240101"));
    }

    @Test
    void mixedTags() {
        assertEquals(List.of("v1.10.0", "v1.9.0", "v1.9.0-rc1", "v1.2.0", "latest"),
                sortDesc("v1.2.0", "latest", "v1.10.0", "v1.9.0-rc1", "v1.9.0"));
    }
}
