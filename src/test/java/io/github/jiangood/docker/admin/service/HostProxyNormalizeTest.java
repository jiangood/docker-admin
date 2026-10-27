package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.entity.Host;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;

/**
 * 主机连接配置归一化（不需要 Spring 上下文）：代理只在远程 TCP 下生效，
 * 界面一行地址会被拆成落库字段，密码回显为 *** 并在保存时沿用旧值。
 */
class HostProxyNormalizeTest {

    @Test
    void parsesSingleLineProxyUrlForTcp() {
        Host host = tcpHost();
        host.setProxyUrl(" http://user:p%40ss@10.0.0.1:8080/ignored ");

        service().normalize(host);

        assertEquals("tcp", host.getConnectionType());
        assertEquals("tcp://192.168.1.2:2375", host.getDockerHost());
        assertEquals("http", host.getProxyType());
        assertEquals("10.0.0.1", host.getProxyHost());
        assertEquals(8080, host.getProxyPort());
        assertEquals("user", host.getProxyUsername());
        assertEquals("p@ss", host.getProxyPassword());
        // 回显值里的密码只留占位符
        assertEquals("http://user:***@10.0.0.1:8080", host.getProxyUrl());
    }

    @Test
    void blankProxyUrlClearsSavedFields() {
        Host host = tcpHost();
        host.setProxyUrl("");
        host.setProxyType("http");
        host.setProxyHost("10.0.0.1");
        host.setProxyPort(8080);
        host.setProxyUsername("user");
        host.setProxyPassword("secret");

        service().normalize(host);

        assertEquals("none", host.getProxyType());
        assertNull(host.getProxyHost());
        assertNull(host.getProxyPort());
        assertNull(host.getProxyUsername());
        assertNull(host.getProxyPassword());
        assertEquals("", host.getProxyUrl());
    }

    @Test
    void clearsProxyForNonTcpConnection() {
        Host host = new Host();
        host.setConnectionType("ssh");
        host.setSshHost("192.168.1.2");
        host.setProxyUrl("socks5://10.0.0.1:1080");
        host.setProxyType("socks5");
        host.setProxyHost("10.0.0.1");
        host.setProxyPort(1080);

        service().normalize(host);

        assertEquals("none", host.getProxyType());
        assertNull(host.getProxyHost());
        assertNull(host.getProxyPort());
        assertEquals("", host.getProxyUrl());
    }

    @Test
    void legacySplitFieldsStillWorkWhenProxyUrlIsAbsent() {
        Host host = tcpHost();
        host.setProxyType("SOCKS5");
        host.setProxyHost(" 10.0.0.1 ");
        host.setProxyPort(1080);
        host.setProxyUsername("user");

        service().normalize(host);

        assertEquals("socks5", host.getProxyType());
        assertEquals("10.0.0.1", host.getProxyHost());
        assertEquals("socks5://user@10.0.0.1:1080", host.getProxyUrl());
    }

    @Test
    void rejectsProxyWithoutPort() {
        Host host = tcpHost();
        host.setProxyUrl("http://10.0.0.1");

        assertThrows(IllegalArgumentException.class, () -> service().normalize(host));
    }

    @Test
    void rejectsProxyWithoutHost() {
        Host host = tcpHost();
        host.setProxyUrl("socks5://:1080");

        assertThrows(IllegalArgumentException.class, () -> service().normalize(host));
    }

    @Test
    void maskPasswordKeepsSavedOne() {
        Host old = tcpHost();
        old.setProxyType("http");
        old.setProxyHost("10.0.0.1");
        old.setProxyPort(8080);
        old.setProxyUsername("user");
        old.setProxyPassword("secret");

        HostService service = Mockito.spy(service());
        doReturn(Optional.of(old)).when(service).findById("h1");

        Host input = tcpHost();
        input.setId("h1");
        input.setProxyUrl("http://user:***@10.0.0.1:8080");

        service.normalize(input);
        service.fillProxyPassword(input);

        assertEquals("secret", input.getProxyPassword());
        assertEquals("http://user:***@10.0.0.1:8080", input.getProxyUrl());
    }

    @Test
    void maskPasswordOnNewHostMeansEmptyPassword() {
        Host input = tcpHost();
        input.setProxyUrl("http://user:***@10.0.0.1:8080");

        service().normalize(input);
        service().fillProxyPassword(input);

        assertNull(input.getProxyPassword());
        assertEquals("http://user@10.0.0.1:8080", input.getProxyUrl());
    }

    @Test
    void noUsernameDropsSavedPassword() {
        Host old = tcpHost();
        old.setProxyPassword("secret");

        HostService service = Mockito.spy(service());
        doReturn(Optional.of(old)).when(service).findById("h1");

        Host input = tcpHost();
        input.setId("h1");
        input.setProxyUrl("http://10.0.0.1:8080");

        service.normalize(input);
        service.fillProxyPassword(input);

        assertNull(input.getProxyUsername());
        assertNull(input.getProxyPassword());
        assertEquals("http://10.0.0.1:8080", input.getProxyUrl());
    }

    private static HostService service() {
        return new HostService(null, null);
    }

    private static Host tcpHost() {
        Host host = new Host();
        host.setConnectionType("tcp");
        host.setDockerHost("192.168.1.2:2375");
        return host;
    }
}
