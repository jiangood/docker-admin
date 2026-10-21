package io.github.jiangood.docker.base.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitToolCloneDirTest {

    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    @TempDir
    Path tempDir;

    @Test
    void cloneDirIsNamedByProjectName() {
        File dir = GitTool.cloneDir("demo");
        assertEquals("demo", dir.getName());
        assertEquals(new File(GitTool.CLONE_BASE_DIR, "demo").getAbsolutePath(), dir.getAbsolutePath());
    }

    @Test
    void illegalCharactersAreReplaced() {
        assertEquals("my_proj_web", GitTool.cloneDir("my proj/web").getName());
        assertEquals("a_b_c", GitTool.cloneDir("a\\b:c").getName());
        assertEquals("project", GitTool.cloneDir("   ").getName());
    }

    @Test
    void dotNamesCannotEscapeCloneBaseDir() {
        assertEquals("project", GitTool.cloneDir(".").getName());
        assertEquals("project", GitTool.cloneDir("..").getName());
    }

    @Test
    void cleanupRemovesOnlyExpiredDirs() throws IOException {
        File expired = newDir("expired");
        File fresh = newDir("fresh");
        long expireTime = System.currentTimeMillis() - 10 * ONE_DAY_MILLIS;
        assertTrue(expired.setLastModified(expireTime - 1000));

        GitTool.CleanupResult result = GitTool.cleanupCloneDirs(tempDir.toFile(), expireTime);

        assertEquals(1, result.getDeletedDirs());
        assertTrue(result.getReleasedBytes() > 0);
        assertFalse(expired.exists());
        assertTrue(fresh.isDirectory());
    }

    @Test
    void cleanupIsNoopWhenBaseDirIsAbsent() {
        GitTool.CleanupResult result = GitTool.cleanupCloneDirs(new File(tempDir.toFile(), "absent"),
                System.currentTimeMillis());

        assertEquals(0, result.getDeletedDirs());
        assertEquals(0, result.getReleasedBytes());
    }

    private File newDir(String name) throws IOException {
        File dir = new File(tempDir.toFile(), name);
        Files.createDirectories(dir.toPath());
        Files.writeString(dir.toPath().resolve("a.txt"), "hello");
        return dir;
    }
}
