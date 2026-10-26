package io.github.jiangood.docker.admin.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectServiceTest {

    private static final LocalDateTime BUILD_TIME = LocalDateTime.of(2026, 10, 20, 15, 30);

    @Test
    void branchTagContainsBuildTimeAndCommitId() {
        assertEquals("main-20261020-1530-1a2b3c4d",
                ProjectService.buildBranchImageTag("main", "1a2b3c4d", BUILD_TIME));
    }

    @Test
    void slashInBranchIsReplaced() {
        assertEquals("feature-login-20261020-1530-1a2b3c4d",
                ProjectService.buildBranchImageTag("feature/login", "1a2b3c4d", BUILD_TIME));
    }

    @Test
    void blankOrUnsupportedBranchFallsBackToDefaultName() {
        assertEquals("branch-20261020-1530-1a2b3c4d",
                ProjectService.buildBranchImageTag("   ", "1a2b3c4d", BUILD_TIME));
        assertEquals("branch-20261020-1530-1a2b3c4d",
                ProjectService.buildBranchImageTag("中文分支", "1a2b3c4d", BUILD_TIME));
        assertEquals("branch-20261020-1530",
                ProjectService.buildBranchImageTag(null, null, BUILD_TIME));
    }

    @Test
    void leadingAndTrailingSeparatorsAreRemoved() {
        assertEquals("release-v1.2-20261020-1530-1a2b3c4d",
                ProjectService.buildBranchImageTag("-release/v1.2.", "1a2b3c4d", BUILD_TIME));
    }

    @Test
    void longBranchNameIsTruncatedButTimeAndCommitKept() {
        String longBranch = "a".repeat(200);
        String tag = ProjectService.buildBranchImageTag(longBranch, "1a2b3c4d", BUILD_TIME);
        assertEquals(128, tag.length());
        assertTrue(tag.endsWith("-20261020-1530-1a2b3c4d"));
    }

    @Test
    void nullBuildTimeFallsBackToNow() {
        String tag = ProjectService.buildBranchImageTag("main", "1a2b3c4d", null);
        assertTrue(tag.matches("main-\\d{8}-\\d{4}-1a2b3c4d"), tag);
    }

    @Test
    void pushBothVersionAndLatestByDefault() {
        assertEquals(List.of("repo:1.0.0", "repo:latest"),
                ProjectService.pushTags(false, "repo:1.0.0", "repo:latest"));
    }

    @Test
    void pushOnlyLatestWhenLatestOnlyEnabled() {
        assertEquals(List.of("repo:latest"),
                ProjectService.pushTags(true, "repo:1.0.0", "repo:latest"));
    }
}
