package io.github.jiangood.docker.admin.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 一行代理地址的解析与回显，规则与邮件代理保持一致；
 * 区别是 SOCKS5 也允许账号密码（平台自己实现 RFC 1929 握手）。
 */
class HostProxySettingsTest {

    @Test
    void blankUrlMeansNoProxy() {
        assertFalse(HostProxySettings.parse(null).enabled());
        assertFalse(HostProxySettings.parse("   ").enabled());
        assertEquals("", HostProxySettings.parse("").displayUrl());
    }

    @Test
    void parseUrlWithoutSchemeDefaultsToHttp() {
        HostProxySettings proxy = HostProxySettings.parse(" 192.168.1.10:8080 ");
        assertEquals(HostProxySettings.Type.HTTP, proxy.type());
        assertEquals("192.168.1.10", proxy.host());
        assertEquals(8080, proxy.port());
        assertNull(proxy.username());
        assertNull(proxy.password());
        assertEquals("http://192.168.1.10:8080", proxy.displayUrl());
    }

    @Test
    void parseUrlReadsSchemeAndCredentials() {
        HostProxySettings socks = HostProxySettings.parse("socks5://user:pass@192.168.1.10:1080");
        assertEquals(HostProxySettings.Type.SOCKS5, socks.type());
        assertEquals("user", socks.username());
        assertEquals("pass", socks.password());
        assertEquals(1080, socks.port());
        assertEquals("socks5://user:***@192.168.1.10:1080", socks.displayUrl());

        HostProxySettings http = HostProxySettings.parse("http://user:pass@192.168.1.10:8080/ignored");
        assertEquals(HostProxySettings.Type.HTTP, http.type());
        assertEquals("pass", http.password());
        assertEquals("http://user:***@192.168.1.10:8080", http.displayUrl());
    }

    @Test
    void parseUrlDecodesPercentEncodedCredentials() {
        HostProxySettings proxy = HostProxySettings.parse("http://user:p%40ss%3A1@192.168.1.10:8080");
        assertEquals("p@ss:1", proxy.password());
    }

    @Test
    void parseUrlKeepsIpv6LiteralReadable() {
        HostProxySettings proxy = HostProxySettings.parse("http://[::1]:8080");
        assertEquals("::1", proxy.host());
        assertEquals(8080, proxy.port());
        assertEquals("http://[::1]:8080", proxy.displayUrl());
    }

    @Test
    void parseUrlLeavesMaskAsPassword() {
        // 回显值直接提交回来时保留占位符，由 Service 用旧密码替换
        HostProxySettings proxy = HostProxySettings.parse("http://user:***@192.168.1.10:8080");
        assertEquals(HostProxySettings.PASSWORD_MASK, proxy.password());
        assertEquals("http://user:***@192.168.1.10:8080", proxy.displayUrl());
    }

    @Test
    void parseUrlRejectsIncompleteOrUnsupportedInput() {
        // 缺端口
        assertThrows(IllegalArgumentException.class, () -> HostProxySettings.parse("http://192.168.1.10"));
        // 端口越界
        assertThrows(IllegalArgumentException.class, () -> HostProxySettings.parse("http://192.168.1.10:99999"));
        // 不支持的 scheme
        assertThrows(IllegalArgumentException.class, () -> HostProxySettings.parse("ftp://192.168.1.10:21"));
        // 缺主机
        assertThrows(IllegalArgumentException.class, () -> HostProxySettings.parse("socks5://:1080"));
    }

    @Test
    void disabledProxyHasEmptyUrl() {
        assertEquals("", HostProxySettings.DISABLED.displayUrl());
    }
}
