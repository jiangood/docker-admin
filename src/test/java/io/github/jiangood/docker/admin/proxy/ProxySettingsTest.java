package io.github.jiangood.docker.admin.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一行代理地址的解析与回显：主机接入与邮件发送共用同一套规则。
 */
class ProxySettingsTest {

    @Test
    void typeParsingIsLenient() {
        assertEquals(ProxySettings.Type.NONE, ProxySettings.Type.parse(null));
        assertEquals(ProxySettings.Type.NONE, ProxySettings.Type.parse(""));
        assertEquals(ProxySettings.Type.NONE, ProxySettings.Type.parse("none"));
        assertEquals(ProxySettings.Type.NONE, ProxySettings.Type.parse("unknown"));
        assertEquals(ProxySettings.Type.HTTP, ProxySettings.Type.parse("http"));
        assertEquals(ProxySettings.Type.HTTP, ProxySettings.Type.parse(" HTTP "));
        assertEquals(ProxySettings.Type.HTTP, ProxySettings.Type.parse("https"));
        assertEquals(ProxySettings.Type.SOCKS5, ProxySettings.Type.parse("socks5"));
        assertEquals(ProxySettings.Type.SOCKS5, ProxySettings.Type.parse("socks"));
        assertEquals("socks5", ProxySettings.Type.SOCKS5.code());
    }

    @Test
    void blankUrlMeansNoProxy() {
        assertFalse(ProxySettings.parse(null).enabled());
        assertFalse(ProxySettings.parse("   ").enabled());
        assertEquals("", ProxySettings.parse("").displayUrl());
        assertEquals("", ProxySettings.DISABLED.displayUrl());
    }

    @Test
    void parseUrlWithoutSchemeDefaultsToHttp() {
        ProxySettings proxy = ProxySettings.parse(" 192.168.1.10:8080 ");
        assertEquals(ProxySettings.Type.HTTP, proxy.type());
        assertEquals("192.168.1.10", proxy.host());
        assertEquals(8080, proxy.port());
        assertNull(proxy.username());
        assertNull(proxy.password());
        assertEquals("http://192.168.1.10:8080", proxy.displayUrl());
        assertEquals("192.168.1.10:8080", proxy.address());
    }

    @Test
    void parseUrlReadsSchemeAndCredentials() {
        ProxySettings socks = ProxySettings.parse("socks5://user:pass@192.168.1.10:1080");
        assertEquals(ProxySettings.Type.SOCKS5, socks.type());
        assertEquals("user", socks.username());
        assertEquals("pass", socks.password());
        assertEquals(1080, socks.port());
        // 密码不回传明文，只给占位符
        assertEquals("socks5://user:***@192.168.1.10:1080", socks.displayUrl());

        ProxySettings http = ProxySettings.parse("http://user:pass@192.168.1.10:8080/ignored");
        assertEquals(ProxySettings.Type.HTTP, http.type());
        assertEquals("user", http.username());
        assertEquals("pass", http.password());
        assertEquals("http://user:***@192.168.1.10:8080", http.displayUrl());
    }

    @Test
    void parseUrlDecodesPercentEncodedCredentials() {
        ProxySettings proxy = ProxySettings.parse("http://user:p%40ss%3A1@192.168.1.10:8080");
        assertEquals("p@ss:1", proxy.password());
    }

    @Test
    void parseUrlKeepsIpv6LiteralReadable() {
        ProxySettings proxy = ProxySettings.parse("http://[::1]:8080");
        assertEquals("::1", proxy.host());
        assertEquals(8080, proxy.port());
        assertEquals("http://[::1]:8080", proxy.displayUrl());
    }

    @Test
    void parseUrlLeavesMaskAsPassword() {
        // 回显值直接提交回来时保留占位符，由 Service 用旧密码替换
        ProxySettings proxy = ProxySettings.parse("http://user:***@192.168.1.10:8080");
        assertEquals(ProxySettings.PASSWORD_MASK, proxy.password());
        assertEquals("http://user:***@192.168.1.10:8080", proxy.displayUrl());
    }

    @Test
    void parseUrlRejectsIncompleteOrUnsupportedInput() {
        // 缺端口
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("http://192.168.1.10"));
        // 端口越界
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("http://192.168.1.10:99999"));
        // 不支持的 scheme
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("ftp://192.168.1.10:21"));
        // 缺主机
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("socks5://:1080"));
    }

    @Test
    void ofNormalizesStoredColumns() {
        ProxySettings proxy = ProxySettings.of(" HTTPS ", " 10.0.0.1 ", 0, " ", "secret");
        assertEquals(ProxySettings.Type.HTTP, proxy.type());
        assertEquals("10.0.0.1", proxy.host());
        // 端口 0 视为未填写
        assertNull(proxy.port());
        assertNull(proxy.username());
        assertFalse(proxy.configured());

        ProxySettings disabled = ProxySettings.of(null, null, null, null, null);
        assertEquals(ProxySettings.Type.NONE, disabled.type());
        assertFalse(disabled.enabled());
    }

    @Test
    void maskOrBlankPasswordKeepsStoredOne() {
        ProxySettings masked = new ProxySettings(ProxySettings.Type.HTTP, "10.0.0.1", 8080, "user", "***");
        assertEquals("stored", masked.resolvePassword(" stored "));

        ProxySettings omitted = new ProxySettings(ProxySettings.Type.HTTP, "10.0.0.1", 8080, "user", null);
        assertEquals("stored", omitted.resolvePassword("stored"));

        // 新增主机没有旧密码，就按空处理
        assertNull(masked.resolvePassword(null));

        ProxySettings fresh = new ProxySettings(ProxySettings.Type.HTTP, "10.0.0.1", 8080, "user", "new");
        assertEquals("new", fresh.resolvePassword("stored"));
    }

    @Test
    void anonymousProxyClearsStoredPassword() {
        ProxySettings anonymous = new ProxySettings(ProxySettings.Type.HTTP, "10.0.0.1", 8080, null, "***");
        assertNull(anonymous.resolvePassword("stored"));
    }

    @Test
    void toStringDoesNotLeakPassword() {
        ProxySettings proxy = new ProxySettings(ProxySettings.Type.HTTP, "10.0.0.1", 8080, "user", "secret");
        String text = proxy.toString();
        assertTrue(text.contains("user"), text);
        assertFalse(text.contains("secret"), text);
    }
}
