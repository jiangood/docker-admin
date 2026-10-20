package io.github.jiangood.docker.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 导入 tar 时的目标镜像解析规则：默认沿用 tar 内标签，指定镜像名/版本时改名或改版本。
 */
class ImageImportServiceTest {

    private static final String BASE = "registry.example.com/team";

    @Test
    void keepsTagsFromArchive() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("nginx:1.25", "redis:7"), List.of(), null, null, BASE);

        assertEquals(2, targets.size());
        assertEquals("registry.example.com/team/nginx", targets.get(0).targetRepo());
        assertEquals("nginx", targets.get(0).name());
        assertEquals("1.25", targets.get(0).tag());
        assertEquals("nginx:1.25", targets.get(0).source());
        assertEquals("registry.example.com/team/redis:7", targets.get(1).fullName());
    }

    @Test
    void stripsRegistryAndNamespace() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("localhost:5000/some/team/app:2.0"), List.of(), null, null, BASE);

        assertEquals(1, targets.size());
        assertEquals("registry.example.com/team/app", targets.get(0).targetRepo());
        assertEquals("2.0", targets.get(0).tag());
    }

    @Test
    void defaultsMissingTagToLatest() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("nginx"), List.of(), null, null, BASE);

        assertEquals("latest", targets.get(0).tag());
    }

    @Test
    void tagOverrideAppliesToSingleImage() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("app:old"), List.of(), null, "new", BASE);

        assertEquals("registry.example.com/team/app:new", targets.get(0).fullName());
        assertEquals("app:old", targets.get(0).source());
    }

    @Test
    void nameOverrideRenamesWithoutLosingTag() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("app:1.0"), List.of(), "renamed", null, BASE);

        assertEquals("registry.example.com/team/renamed", targets.get(0).targetRepo());
        assertEquals("renamed", targets.get(0).name());
        assertEquals("1.0", targets.get(0).tag());
    }

    @Test
    void nameAndTagOverrideTogether() {
        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of("app:1.0"), List.of(), "renamed", "2.0", BASE);

        assertEquals("registry.example.com/team/renamed:2.0", targets.get(0).fullName());
        assertEquals("app:1.0", targets.get(0).source());
    }

    @Test
    void untaggedArchiveRequiresNameAndTag() {
        assertThrows(IllegalStateException.class, () -> ImageImportService.resolveTargets(
                List.of(), List.of("sha256:abc"), null, null, BASE));

        List<ImageImportService.ImportTarget> targets = ImageImportService.resolveTargets(
                List.of(), List.of("sha256:abc"), "renamed", "1.0", BASE);
        assertEquals("registry.example.com/team/renamed:1.0", targets.get(0).fullName());
        assertEquals("sha256:abc", targets.get(0).source());
    }

    @Test
    void nameOverrideCannotHandleMultipleImages() {
        assertThrows(IllegalArgumentException.class, () -> ImageImportService.resolveTargets(
                List.of("a:1", "b:1"), List.of(), "renamed", null, BASE));
    }

    @Test
    void tagOverrideCannotHandleMultipleImages() {
        assertThrows(IllegalArgumentException.class, () -> ImageImportService.resolveTargets(
                List.of("a:1", "b:1"), List.of(), null, "2.0", BASE));
    }

    @Test
    void rejectsDuplicateTargets() {
        // 不同命名空间下的同名镜像会落到同一个注册中心目标，无法同时导入
        assertThrows(IllegalArgumentException.class, () -> ImageImportService.resolveTargets(
                List.of("ns1/app:1", "ns2/app:1"), List.of(), null, null, BASE));
    }

}
