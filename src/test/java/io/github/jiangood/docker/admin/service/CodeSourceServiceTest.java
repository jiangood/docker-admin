package io.github.jiangood.docker.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeSourceServiceTest {

    @Test
    void hostKey_httpUrls() {
        assertEquals("10.207.120.48", CodeSourceService.hostKey("http://10.207.120.48"));
        assertEquals("10.207.120.48", CodeSourceService.hostKey("http://10.207.120.48/wenlv/x.git"));
        assertEquals("gitlab.com", CodeSourceService.hostKey("https://gitlab.com"));
        assertEquals("gitlab.com", CodeSourceService.hostKey("https://gitlab.com/a/b.git"));
        assertEquals("gitlab.com", CodeSourceService.hostKey("https://GitLab.com/a/b.git"));
    }

    @Test
    void hostKey_withPort() {
        assertEquals("10.207.120.48:8080", CodeSourceService.hostKey("http://10.207.120.48:8080/a/b.git"));
        assertEquals("10.207.120.48:8080", CodeSourceService.hostKey("10.207.120.48:8080"));
    }

    @Test
    void hostKey_sshAndScp() {
        assertEquals("10.207.120.48", CodeSourceService.hostKey("git@10.207.120.48:jiangtao/models.git"));
        assertEquals("10.207.120.48:2222", CodeSourceService.hostKey("ssh://git@10.207.120.48:2222/jiangtao/models.git"));
    }

    @Test
    void hostKey_bareHost() {
        assertEquals("gitlab.com", CodeSourceService.hostKey("gitlab.com"));
        assertEquals("10.207.120.48", CodeSourceService.hostKey("10.207.120.48"));
    }

    @Test
    void hostKey_blankOrInvalid() {
        assertNull(CodeSourceService.hostKey(null));
        assertNull(CodeSourceService.hostKey("  "));
    }
}
