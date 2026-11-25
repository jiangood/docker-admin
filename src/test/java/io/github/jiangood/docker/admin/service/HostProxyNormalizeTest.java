package io.github.jiangood.docker.admin.service;

import io.github.jiangood.docker.admin.dao.ProxyRepository;
import io.github.jiangood.docker.admin.entity.Host;
import io.github.jiangood.docker.admin.entity.Proxy;
import io.github.jiangood.openadmin.util.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主机连接配置归一化（不需要 Spring 上下文）：
 * 代理只认「代理管理」里选中的 {@code proxyId}，远程 TCP 与 SSH 都生效；
 * 五个落库字段（地址 / 端口 / 账号 / 密码）由代理配置复制，密码回显为 ***。
 */
class HostProxyNormalizeTest {

    private final ProxyRepository proxyRepository = mock(ProxyRepository.class);

    @Test
    void appliesManagedProxyForTcp() {
        when(proxyRepository.findById("p1")).thenReturn(Optional.of(proxy()));

        Host host = tcpHost();
        host.setProxyId("p1");

        service().normalize(host);

        assertEquals("tcp", host.getConnectionType());
        assertEquals("tcp://192.168.1.2:2375", host.getDockerHost());
        assertEquals("http", host.getProxyType());
        assertEquals("10.0.0.1", host.getProxyHost());
        assertEquals(8080, host.getProxyPort());
        assertEquals("user", host.getProxyUsername());
        assertEquals("secret", host.getProxyPassword());
        // 回显值里的密码只留占位符
        assertEquals("http://user:***@10.0.0.1:8080", host.getProxyUrl());
    }

    @Test
    void appliesManagedProxyForSsh() {
        when(proxyRepository.findById("p1")).thenReturn(Optional.of(proxy()));

        Host host = new Host();
        host.setConnectionType("ssh");
        host.setSshHost("192.168.1.2");
        host.setProxyId("p1");

        service().normalize(host);

        assertEquals("http", host.getProxyType());
        assertEquals("10.0.0.1", host.getProxyHost());
        assertEquals(8080, host.getProxyPort());
        assertEquals(22, host.getSshPort());
        assertEquals("root", host.getSshUser());
        assertEquals("http://user:***@10.0.0.1:8080", host.getProxyUrl());
    }

    @Test
    void blankProxyIdClearsSavedFields() {
        Host host = tcpHost();
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
        assertNull(host.getProxyId());
        assertEquals("", host.getProxyUrl());
    }

    @Test
    void localConnectionClearsProxy() {
        Host host = new Host();
        host.setConnectionType("local");
        host.setProxyId("p1");
        host.setProxyType("socks5");
        host.setProxyHost("10.0.0.1");
        host.setProxyPort(1080);

        service().normalize(host);

        assertEquals("none", host.getProxyType());
        assertNull(host.getProxyHost());
        assertNull(host.getProxyPort());
        assertNull(host.getProxyId());
        assertEquals("", host.getProxyUrl());
    }

    @Test
    void unknownProxyIsRejected() {
        when(proxyRepository.findById("gone")).thenReturn(Optional.empty());

        Host host = tcpHost();
        host.setProxyId("gone");

        assertThrows(BusinessException.class, () -> service().normalize(host));
    }

    /**
     * 托管代理的密码是权威值：即使主机旧快照里有密码，也不能在保存时沿用。
     */
    @Test
    void managedProxyPasswordIsNotOverriddenByOldHostSnapshot() {
        Proxy noPasswordProxy = proxy();
        noPasswordProxy.setPassword(null);
        when(proxyRepository.findById("p1")).thenReturn(Optional.of(noPasswordProxy));

        Host old = tcpHost();
        old.setId("h1");
        old.setProxyPassword("old-secret");

        HostService service = Mockito.spy(service());
        doReturn(Optional.of(old)).when(service).findById("h1");

        Host input = tcpHost();
        input.setId("h1");
        input.setProxyId("p1");

        service.normalize(input);
        service.fillProxyPassword(input);

        assertNull(input.getProxyPassword());
        assertEquals("http://user@10.0.0.1:8080", input.getProxyUrl());
    }

    private HostService service() {
        return new HostService(null, null, proxyRepository, null);
    }

    private static Proxy proxy() {
        Proxy proxy = new Proxy();
        proxy.setId("p1");
        proxy.setType("http");
        proxy.setHost("10.0.0.1");
        proxy.setPort(8080);
        proxy.setUsername("user");
        proxy.setPassword("secret");
        return proxy;
    }

    private static Host tcpHost() {
        Host host = new Host();
        host.setConnectionType("tcp");
        host.setDockerHost("192.168.1.2:2375");
        return host;
    }
}
