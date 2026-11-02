package io.github.jiangood.docker.admin.util;

import io.github.jiangood.openadmin.util.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostRootPolicyTest {

    @Test
    void defaultRootAndNormalization() {
        assertEquals("/home", HostRootPolicy.normalizeRoot(null));
        assertEquals("/home", HostRootPolicy.normalizeRoot("  "));
        assertEquals("/home", HostRootPolicy.normalizeRoot("/home/"));
        assertEquals("/data", HostRootPolicy.normalizeRoot("//data"));
        assertEquals("/opt/app", HostRootPolicy.normalizeRoot("/opt/./app/"));
    }

    @Test
    void rootAndWindowsPathsAreRejected() {
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRoot("D:\\data"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRoot("D:/data"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRoot("home"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRoot("/home/../etc"));
    }

    @Test
    void systemDirectoriesAreRejected() {
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/etc"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/etc/nginx"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/var"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/var/lib"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/var/lib/docker"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/run"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/mnt"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/host_mnt"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/host"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/tmp"));
    }

    @Test
    void dataDirectoriesAreAllowed() {
        HostRootPolicy.assertValidRoot("/home");
        HostRootPolicy.assertValidRoot("/home/app");
        HostRootPolicy.assertValidRoot("/data");
        HostRootPolicy.assertValidRoot("/opt/app");
        HostRootPolicy.assertValidRoot("/srv/app");
        HostRootPolicy.assertValidRoot("/var/www");
    }

    @Test
    void dockerDesktopAndWslDriveViewsAreAllowedButNotDriveRootsOrWindowsDirs() {
        HostRootPolicy.assertValidRoot("/run/desktop/mnt/host/d/data");
        HostRootPolicy.assertValidRoot("/host_mnt/d/data");
        HostRootPolicy.assertValidRoot("/mnt/d/data");

        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/run/desktop/mnt/host/d"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/mnt/c"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/mnt/c/Windows"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/mnt/c/Program Files"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.assertValidRoot("/run/desktop/mnt/host/c/ProgramData"));
    }

    @Test
    void relativePathsStayInsideTheMount() {
        assertEquals("", HostRootPolicy.normalizeRelativePath(null));
        assertEquals("", HostRootPolicy.normalizeRelativePath("/"));
        assertEquals("a/b", HostRootPolicy.normalizeRelativePath("/a//b/"));
        assertEquals("a/b", HostRootPolicy.normalizeRelativePath("./a/b"));

        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRelativePath("../a"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRelativePath("a/../../b"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeRelativePath("a\\b"));
    }

    @Test
    void agentPathMapping() {
        assertEquals("/host", HostRootPolicy.toAgentPath(""));
        assertEquals("/host/a/b", HostRootPolicy.toAgentPath("a/b"));
        assertEquals("", HostRootPolicy.fromAgentPath("/host"));
        assertEquals("a/b", HostRootPolicy.fromAgentPath("/host/a/b"));
    }

    @Test
    void namesAndJoin() {
        assertEquals("app.yml", HostRootPolicy.joinRelative("", "app.yml"));
        assertEquals("conf/app.yml", HostRootPolicy.joinRelative("conf", "app.yml"));
        assertEquals("conf/app.yml", HostRootPolicy.joinRelative("", "conf/app.yml"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeName("a/b"));
        assertThrows(BusinessException.class, () -> HostRootPolicy.normalizeName(".."));
        assertThrows(BusinessException.class, () -> HostRootPolicy.joinRelative("a", "../b"));
    }
}
