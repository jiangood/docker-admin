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
}
