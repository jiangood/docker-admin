package io.github.jiangood.docker.admin.util;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildContextUtilsTest {

    @Test
    void blankAndSlashMeanRepositoryRoot() {
        assertEquals("", BuildContextUtils.normalize(null));
        assertEquals("", BuildContextUtils.normalize(""));
        assertEquals("", BuildContextUtils.normalize("   "));
        assertEquals("", BuildContextUtils.normalize("/"));
        assertEquals("", BuildContextUtils.normalize("./"));
        assertEquals("", BuildContextUtils.normalize("\\"));
    }

    @Test
    void nestedPathIsNormalized() {
        assertEquals("admin", BuildContextUtils.normalize(" admin "));
        assertEquals("admin", BuildContextUtils.normalize("/admin/"));
        assertEquals("admin", BuildContextUtils.normalize("./admin"));
        assertEquals("admin/web", BuildContextUtils.normalize("admin\\web"));
        assertEquals("admin/web", BuildContextUtils.normalize("./admin//web/"));
    }

    @Test
    void traversalAndAbsolutePathsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalize(".."));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalize("../admin"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalize("admin/../model"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalize("D:/ws/cvs/admin"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalize("C:\\ws\\cvs\\admin"));
    }

    @Test
    void displayFallsBackToRoot() {
        assertEquals("仓库根目录", BuildContextUtils.display(null));
        assertEquals("仓库根目录", BuildContextUtils.display("/"));
        assertEquals("admin", BuildContextUtils.display("./admin/"));
    }

    @Test
    void dockerfileIsNormalizedToRepoRelativePath() {
        assertEquals("Dockerfile", BuildContextUtils.normalizeDockerfile(null));
        assertEquals("Dockerfile", BuildContextUtils.normalizeDockerfile("  "));
        assertEquals("Dockerfile", BuildContextUtils.normalizeDockerfile("/"));
        assertEquals("admin/Dockerfile", BuildContextUtils.normalizeDockerfile("/admin/Dockerfile"));
        assertEquals("admin/Dockerfile", BuildContextUtils.normalizeDockerfile("./admin/Dockerfile"));
        assertEquals("admin/Dockerfile", BuildContextUtils.normalizeDockerfile("admin\\Dockerfile"));
        assertEquals("admin/web/Dockerfile", BuildContextUtils.normalizeDockerfile("admin//web/Dockerfile"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalizeDockerfile("../Dockerfile"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalizeDockerfile("admin/../Dockerfile"));
        assertThrows(IllegalArgumentException.class, () -> BuildContextUtils.normalizeDockerfile("D:/ws/cvs/Dockerfile"));
    }

    @Test
    void rootResolvesToWorkDirItself() {
        File workDir = new File("build-work");
        assertEquals(workDir, BuildContextUtils.resolve(workDir, null));
        assertEquals(workDir, BuildContextUtils.resolve(workDir, "/"));
    }

    @Test
    void dockerfileMustStayInsideContext() {
        File workDir = new File("build-work");
        File contextDir = BuildContextUtils.resolve(workDir, "admin");
        assertTrue(BuildContextUtils.isInside(contextDir, new File(workDir, "admin/Dockerfile")));
        assertTrue(BuildContextUtils.isInside(contextDir, new File(workDir, "admin/web/Dockerfile")));
        assertFalse(BuildContextUtils.isInside(contextDir, new File(workDir, "Dockerfile")));
        assertFalse(BuildContextUtils.isInside(contextDir, new File(workDir, "model/Dockerfile")));
    }
}
