package io.github.jiangood.docker.admin.util;

import io.github.jiangood.openadmin.util.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentImageUtilsTest {

    @Test
    void tagDefaultsToLatest() {
        assertEquals("latest", AgentImageUtils.normalizeTag(null));
        assertEquals("latest", AgentImageUtils.normalizeTag("  "));
        assertEquals("latest", AgentImageUtils.normalizeTag("latest"));
        assertEquals("v4.8.40", AgentImageUtils.normalizeTag("v4.8.40"));
        assertEquals("compose_v1", AgentImageUtils.normalizeTag("compose_v1"));
    }

    @Test
    void invalidTagsAreRejected() {
        assertThrows(BusinessException.class, () -> AgentImageUtils.normalizeTag("a/b"));
        assertThrows(BusinessException.class, () -> AgentImageUtils.normalizeTag("a:b"));
        assertThrows(BusinessException.class, () -> AgentImageUtils.normalizeTag("a b"));
        assertThrows(BusinessException.class, () -> AgentImageUtils.normalizeTag("-bad"));
    }

    @Test
    void repositoryRejectsTagAndDigest() {
        assertEquals(AgentImageUtils.DEFAULT_REPOSITORY, AgentImageUtils.normalizeRepository(null));
        assertEquals("ghcr.io/jiangood/docker-admin-agent",
                AgentImageUtils.normalizeRepository(" ghcr.io/jiangood/docker-admin-agent "));
        assertEquals("registry.example.com:5000/foo/agent",
                AgentImageUtils.normalizeRepository("registry.example.com:5000/foo/agent"));

        assertThrows(BusinessException.class,
                () -> AgentImageUtils.normalizeRepository("ghcr.io/jiangood/docker-admin-agent:latest"));
        assertThrows(BusinessException.class,
                () -> AgentImageUtils.normalizeRepository("ghcr.io/jiangood/docker-admin-agent@sha256:abc"));
        assertThrows(BusinessException.class,
                () -> AgentImageUtils.normalizeRepository("ghcr.io/jiangood/docker-admin-agent/"));
    }

    @Test
    void resolvesRepositoryAndTag() {
        assertEquals("ghcr.io/jiangood/docker-admin-agent:latest",
                AgentImageUtils.resolveImage("ghcr.io/jiangood/docker-admin-agent", "latest"));
        assertEquals("ghcr.io/jiangood/docker-admin-agent:v4.8.40",
                AgentImageUtils.resolveImage(null, "v4.8.40"));
    }

    @Test
    void outdatedRequiresComparableVersions() {
        String repo = AgentImageUtils.DEFAULT_REPOSITORY;

        // 版本号可读且不同 → 落后（用户固定旧 tag 也算，这正是要提示的场景）
        assertEquals(true, AgentImageUtils.isOutdated(repo, "v4.8.40", "4.8.88"));
        // 同版本 → 不落后
        assertEquals(false, AgentImageUtils.isOutdated(repo, "v4.8.88", "4.8.88"));
        // 自定义镜像仓库：版本号与平台没有对应关系，不判
        assertEquals(false, AgentImageUtils.isOutdated("registry.local/docker-admin-agent", "v4.8.40", "4.8.88"));
        // 旧镜像没写版本 label / 本地 dev 构建 / 平台版本读不到：都没有可比对象，不判
        assertEquals(false, AgentImageUtils.isOutdated(repo, null, "4.8.88"));
        assertEquals(false, AgentImageUtils.isOutdated(repo, AgentImageUtils.DEV_VERSION, "4.8.88"));
        assertEquals(false, AgentImageUtils.isOutdated(repo, "v4.8.40", null));
    }
}
