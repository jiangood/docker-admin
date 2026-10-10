package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.entity.CodeSource;
import io.github.jiangood.docker.admin.entity.CodeSourceAuthType;
import io.github.jiangood.docker.base.tool.GitCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void credential_nullSourceIsAnonymous() {
        assertTrue(CodeSourceService.resolveCredential(null).isNone());
    }

    @Test
    void credential_token() {
        CodeSource source = new CodeSource();
        source.setAuthType(CodeSourceAuthType.TOKEN);
        source.setUsername("oauth2");
        source.setToken("glpat-xxx");

        GitCredential credential = CodeSourceService.resolveCredential(source);
        assertEquals(GitCredential.Kind.TOKEN, credential.getKind());
        assertEquals("oauth2", credential.getUsername());
        assertEquals("glpat-xxx", credential.getSecret());
    }

    @Test
    void credential_tokenWithoutTokenFallsBackToAnonymous() {
        CodeSource source = new CodeSource();
        source.setAuthType(CodeSourceAuthType.TOKEN);

        assertTrue(CodeSourceService.resolveCredential(source).isNone());
    }

    @Test
    void credential_password() {
        CodeSource source = new CodeSource();
        source.setAuthType(CodeSourceAuthType.PASSWORD);
        source.setUsername("alice");
        source.setPassword("secret");

        GitCredential credential = CodeSourceService.resolveCredential(source);
        assertEquals(GitCredential.Kind.PASSWORD, credential.getKind());
        assertEquals("alice", credential.getUsername());
        assertEquals("secret", credential.getSecret());
    }

    @Test
    void credential_sshKey() {
        CodeSource source = new CodeSource();
        source.setAuthType(CodeSourceAuthType.SSH_KEY);
        source.setPrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----");
        source.setPrivateKeyPassphrase("1234");

        GitCredential credential = CodeSourceService.resolveCredential(source);
        assertEquals(GitCredential.Kind.SSH_KEY, credential.getKind());
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", credential.getPrivateKey());
        assertEquals("1234", credential.getPassphrase());
    }

    @Test
    void credential_sshKeyWithoutKeyFallsBackToAnonymous() {
        CodeSource source = new CodeSource();
        source.setAuthType(CodeSourceAuthType.SSH_KEY);

        assertTrue(CodeSourceService.resolveCredential(source).isNone());
    }
}
